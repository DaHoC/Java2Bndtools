package com.qivicon.testbuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.jarpackager.IJarExportRunnable;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

import aQute.bnd.build.Workspace;
import aQute.bnd.service.RepositoryPlugin;

/**
 * Builder for zipping compiled build artifacts of an arbitrary project 
 * into an JAR bundle file and copy it to a well-defined bnd workspace repository location.
 * 
 * @see <a href=
 *      "http://help.eclipse.org/photon/index.jsp?topic=%2Forg.eclipse.platform.doc.isv%2Fguide%2FresAdv_builders.htm&cp=2_0_11_1">official
 *      Eclipse Photon documentation about incremental builders</a>
 * 
 *      <a href=
 *      "http://www.eclipse.org/articles/Article-Builders/builders.html">article
 *      about Eclipse project builders and natures</a>
 */
public class QiviconBuilder extends IncrementalProjectBuilder {

	static final boolean DEBUG_OUTPUT = true;
	static final String BUILDER_ID = "com.qivicon.testbuilder.qiviconbuilder";
	static final String BUILDER_NAME = "QIVICON bnd builder";
	static final String MANIFEST_LOCATION = "META-INF/MANIFEST.MF";
	static final int INTERNAL_ERROR = -10001;

	/**
	 * The well-defined name of the bnd workspace repository that the artifacts are
	 * deployed to if the repo is present. "Local" is a repository created by
	 * default, but may be missing if user decided to remove it
	 */
	static final String BND_WORKSPACE_REPO_NAME = "Local";
	
	private MessageConsoleStream consoleStream;

	@Override
	protected IProject[] build(int kind, @SuppressWarnings("rawtypes") Map args, IProgressMonitor monitor) throws CoreException {
		// kind is one of FULL_BUILD, INCREMENTAL_BUILD, AUTO_BUILD, CLEAN_BUILD
		switch (kind) {
		default:
		case IncrementalProjectBuilder.FULL_BUILD:
		case IncrementalProjectBuilder.CLEAN_BUILD:
			fullBuild(monitor);
			break;
		case IncrementalProjectBuilder.INCREMENTAL_BUILD:
		case IncrementalProjectBuilder.AUTO_BUILD:
			final IResourceDelta delta = getDelta(getProject());
			if (delta == null) {
				fullBuild(monitor);
			} else {
				incrementalBuild(delta, monitor);
			}
			break;
		}
		return new IProject[0];
	}

	protected void incrementalBuild(final IResourceDelta delta, final IProgressMonitor monitor) throws CoreException {
		// TODO Implement me
		log(String.format("%s: Delta changes of %s (nothing done at the moment)", BUILDER_ID, delta.getFullPath().toString()));
	}

	protected void fullBuild(final IProgressMonitor monitor) throws CoreException {
		boolean builderOrderOK = checkBuilderOrdering();
		if (!builderOrderOK) {
			// TODO We can automatically swap the builders when the order is wrong, i.e. fix this on-the-fly
			log(String.format("%s: Bad builder order for project %s!", BUILDER_ID, getProject().getName()));
			return;
		}

		log(String.format("%s: Exporting project %s", BUILDER_ID, getProject().getName()));

		// Create a temporary jar package exported from the project
		final Optional<File> exportedProjectJarFileOpt = getExportedProjectJarFile(monitor);
		if (!exportedProjectJarFileOpt.isPresent()) {
			// Skipping this project
			return;
		}

		// Provide this jar file to the bnd repository
		copyJarFileIntoBndWorkspaceRepository(exportedProjectJarFileOpt.get());

		// Delete the temporary jar file again upon exit
		exportedProjectJarFileOpt.get().deleteOnExit();
	}

	private Optional<File> getExportedProjectJarFile(final IProgressMonitor monitor) throws CoreException {
		// Skip this project and display / log a hint that the builder could not proceed
		final Optional<IPath> manifestLocationOpt = getManifestLocation();
		if (!manifestLocationOpt.isPresent()) {
			final String errorMessage = String.format("%s project %s: %s file not found, skipping project!", BUILDER_ID, getProject().getName(), MANIFEST_LOCATION);
			log(errorMessage);
			return Optional.empty();
		}

		final IJavaProject jproject = JavaCore.create(getProject());
		// Check if this project is even a Java project
		if (jproject == null) {
			final String errorMessage = String.format("%s project %s is not a Java project (anymore)!", BUILDER_ID, getProject().getName());
			log(errorMessage);
			return Optional.empty();
		}

		// Reuse and invoke the internal Eclipse JAR exporter
		// jarPackage is an object containing all required information to make an export
		final JarPackageData jarPackage = new JarPackageData();
		// Explicitly add the project Manifest
		jarPackage.setManifestLocation(manifestLocationOpt.get());
		jarPackage.setUsesManifest(true);
		jarPackage.setSaveManifest(false);
		jarPackage.setGenerateManifest(false);
		jarPackage.setReuseManifest(false);
		jarPackage.setElements(new IJavaProject[] {jproject});
		jarPackage.setExportClassFiles(true);
		jarPackage.setExportOutputFolders(true);
		jarPackage.setExportJavaFiles(false);
		jarPackage.setRefactoringAware(false);
		jarPackage.setUseSourceFolderHierarchy(true);
		jarPackage.setSaveDescription(false);
		jarPackage.setDescriptionLocation(new Path(""));
		jarPackage.setCompress(true);
		jarPackage.setIncludeDirectoryEntries(true);
		jarPackage.setOverwrite(true);
		jarPackage.setBuildIfNeeded(true);

		File tmpFile = null;
		try {
			final String filename = (getProject().getName() != null ? getProject().getName() : BUILDER_ID);
			tmpFile = File.createTempFile(filename + "-", ".jar");
		} catch (IOException e) {
			final String errorMessage = String.format("%s project %s: could not create temporary jar file!", BUILDER_ID, getProject().getName());
			throw createCoreException(errorMessage, e);
		}
		final IPath jarPath = Path.fromOSString(tmpFile.getAbsolutePath());

		jarPackage.setJarLocation(jarPath);

		final IJarExportRunnable jarFileExportOperation = jarPackage.createJarExportRunnable(null);
		try {
			jarFileExportOperation.run(monitor);
		} catch (InvocationTargetException e) {
			final String errorMessage = String.format("%s project %s: jar file export failed!", BUILDER_ID, getProject().getName());
			throw createCoreException(errorMessage, e);
		} catch (InterruptedException e) {
			// Restore interrupted state
			Thread.currentThread().interrupt();
			final String errorMessage = String.format("%s project %s: jar file export interrupted!", BUILDER_ID, getProject().getName());
			throw createCoreException(errorMessage, e);
		}
		return Optional.of(jarPackage.getJarLocation().toFile());
	}

	private void copyJarFileIntoBndWorkspaceRepository(final File jarFile) throws CoreException {
		// JAR file should have been created, copy it into the bndtools workspace repo
		final Optional<RepositoryPlugin> bndWorkspaceRepository = getBndWorkspaceRepository();
		if (!bndWorkspaceRepository.isPresent()) {
			return;
		}
		try (final InputStream jarFileInputStream = new FileInputStream(jarFile)) {
			log(String.format("Copying file %s into %s", jarFile.getAbsolutePath(), bndWorkspaceRepository.get().getLocation()));
			// Put the given bundle as stream into the given bnd workspace repository.
			bndWorkspaceRepository.get().put(jarFileInputStream, null);
		} catch (IOException e) {
			final String errorMessage = String.format("%s project %s: Error accessing jar bundle file %s!", BUILDER_ID, getProject().getName(), jarFile.getAbsolutePath());
			throw createCoreException(errorMessage, e);
		} catch (Exception e) {
			final String errorMessage = String.format("%s project %s: Could not copy jar bundle file %s into bnd workspace repository %s!", BUILDER_ID, getProject().getName(), jarFile.getAbsolutePath(), bndWorkspaceRepository.get().getLocation());
			throw createCoreException(errorMessage, e);
		}
	}

	/**
	 * Get the manifest from project.
	 * 
	 * @return optional containing manifest location or empty optional if not found
	 */
	private Optional<IPath> getManifestLocation() {
		final IResource manifestFileResource = getProject().findMember(MANIFEST_LOCATION);
		if (manifestFileResource == null || !manifestFileResource.exists()) {
			// Skip or abort builder, as it will not be a valid JAR file without MANIFEST
			return Optional.empty();
		}
		return Optional.ofNullable(manifestFileResource.getFullPath());
	}

	private Optional<RepositoryPlugin> getBndWorkspaceRepository() throws CoreException {
		final File bndWorkspaceDirectory = getBndWorkspaceDirectory();
		if (bndWorkspaceDirectory == null) {
			log(String.format("%s project %s: bnd workspace directory could not be determined!", BUILDER_ID, getProject().getName()));
			return Optional.empty();
		}
		try {
			final Workspace bndWorkspace = Workspace.getWorkspace(bndWorkspaceDirectory);
			if (bndWorkspace == null) {
				log(String.format("%s project %s: bnd workspace could not be retrieved!", BUILDER_ID, getProject().getName()));
				return Optional.empty();
			}
			// Prerequisite: bnd workspace repository with well-defined name BND_WORKSPACE_REPO_NAME must be present
			final RepositoryPlugin bndWorkspaceRepository = bndWorkspace.getRepository(BND_WORKSPACE_REPO_NAME);
			if (bndWorkspaceRepository == null) {
				log(String.format("%s project %s: bnd workspace repository '%s' could not be retrieved!", BUILDER_ID, getProject().getName(), BND_WORKSPACE_REPO_NAME));
				return Optional.empty();
			}
			return Optional.ofNullable(bndWorkspaceRepository);
		} catch (Exception e) {
			log(String.format("%s project %s: could not obtain bnd workspace repository!", BUILDER_ID, getProject().getName()));
			return Optional.empty();
		}
	}

	private static File getBndWorkspaceDirectory() throws CoreException {
		final IWorkspaceRoot eclipseWorkspace = ResourcesPlugin.getWorkspace().getRoot();
		IProject cnfProject = eclipseWorkspace.getProject(Workspace.BNDDIR);
		if (!cnfProject.exists()) {
			cnfProject = eclipseWorkspace.getProject(Workspace.CNFDIR);
		}
		if (cnfProject.exists()) {
			if (!cnfProject.isOpen()) {
				cnfProject.open(null);
			}
			return cnfProject.getLocation().toFile().getParentFile();
		}
		return null;
	}

	/**
	 * Checks whether this builder is configured to run <b>after</b> the Java
	 * builder.
	 * 
	 * @return <code>true</code> if the builder order is correct, and <code>false</code> otherwise
	 * @exception CoreException if something goes wrong
	 */
	private boolean checkBuilderOrdering() throws CoreException {
		// determine relative builder position from project's buildspec
		final ICommand[] cs = getProject().getDescription().getBuildSpec();
		int qiviconBuilderIndex = -1;
		int javaBuilderIndex = -1;
		for (int i = 0; i < cs.length; i++) {
			if (cs[i].getBuilderName().equals(JavaCore.BUILDER_ID)) {
				javaBuilderIndex = i;
			} else if (cs[i].getBuilderName().equals(BUILDER_ID)) {
				qiviconBuilderIndex = i;
			}
		}
		return qiviconBuilderIndex > javaBuilderIndex;
	}

	/**
	 * Creates a <code>CoreException</code> with the given parameters.
	 *
	 * @param	message		a string with the message
	 * @param	exception	the exception to be wrapped, or <code>null</code> if none
	 * @return a CoreException
	 */
	private static CoreException createCoreException(String message, final Exception exception) {
		if (message == null) {
			message= ""; //$NON-NLS-1$
		}
		return new CoreException(new Status(IStatus.ERROR, BUILDER_ID, INTERNAL_ERROR, message, exception));
	}

	@Override
	protected void startupOnInitialize() {
		// nothing to initialize
	}

	@Override
	protected void clean(IProgressMonitor monitor) {
		if (this.consoleStream != null) {
			try {
				this.consoleStream.close();
			} catch (IOException e) {
				// Hide exception
			}
			consoleStream = null;
		}
	}

	private void log(final String message) {
		if (!DEBUG_OUTPUT) {
			return;
		}
		if (this.consoleStream == null) {
			this.consoleStream = getStreamForLoggingToEclipseConsole();
			this.consoleStream.setActivateOnWrite(true);
		}
		this.consoleStream.println(message);
	}

	private MessageConsoleStream getStreamForLoggingToEclipseConsole() {
		final MessageConsole console = new MessageConsole(BUILDER_NAME, null);
		ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] { console });
		ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
		final MessageConsoleStream stream = console.newMessageStream();
		// Redirect system.err and system.out to the Eclipse console as well
		System.setErr(new PrintStream(stream));
		System.setOut(new PrintStream(stream));
		return stream;
	}
}
