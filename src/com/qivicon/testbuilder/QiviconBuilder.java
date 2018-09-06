package com.qivicon.testbuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.commands.operations.OperationStatus;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
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
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.jarpackager.JarFileExportOperation;
import org.eclipse.jdt.internal.ui.jarpackager.JarPackagerUtil;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;
import org.eclipse.swt.widgets.Shell;

import aQute.bnd.build.Workspace;
import aQute.bnd.service.RepositoryPlugin;
import aQute.bnd.service.RepositoryPlugin.PutResult;

/**
 * Builder for zipping compiled build artifacts of an arbitrary project into an
 * OSGi-compliant JAR bundle file and copy it to a well-defined location, such
 * as a bnd repository location of a bnd workspace.
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

	static final String BUILDER_ID = "com.qivicon.testbuilder.qiviconbuilder";
	/**
	 * The well-defined name of the bnd workspace repository that the artifacts are
	 * deployed to if the repo is present. "Local" is a repository created by
	 * default, but may be missing if user decided to remove it
	 */
	static final String BND_WORKSPACE_REPO_NAME = "Local";
	File targetArchiveFile;

	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
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
		System.out.println("Delta changes of " + delta.getFullPath().toString() + " (nothing done atm)");
	}

	protected void fullBuild(final IProgressMonitor monitor) throws CoreException {
		// TODO We can automatically swap the builders when the order is wrong, i.e. fix this on-the-fly
		boolean builderOrderOK = checkBuilderOrdering();
		if (!builderOrderOK) {
			System.err.println(BUILDER_ID + ": Bad builder order for project " + getProject().getName());
		}

		final File exportedProjectJarFile = getExportedProjectJarFile(monitor);

		copyJarFileIntoBndWorkspaceRepository(exportedProjectJarFile);

		// Delete the temporary file again when shutting down
		exportedProjectJarFile.deleteOnExit();
	}
	
	private File getExportedProjectJarFile(final IProgressMonitor monitor) throws CoreException {
		// TODO Do not make this absemmeln, but rather skip this project or display/log
		// a nice hint that the builder could not proceed
		final Optional<IPath> manifestLocationOpt = getManifestLocation();
		if (!manifestLocationOpt.isPresent()) {
			final String errorMessage = BUILDER_ID + ": " + getProject().getName() + " MANIFEST.MF file not found, aborting build!";
			System.err.println(errorMessage);
			final CoreException coreException = JarPackagerUtil.createCoreException(errorMessage, new UnsupportedOperationException(errorMessage));
			throw coreException;
		}
		System.out.println("Manifest path: " + manifestLocationOpt.get().toString());

		// Reuse and invoke the internal Eclipse JAR exporter
		// jarPackage is an object containing all required information to make an export
		final JarPackageData jarPackage = new JarPackageData();
		// Explicitly add the project Manifest
		jarPackage.setManifestLocation(manifestLocationOpt.get());
		jarPackage.setUsesManifest(true);
		jarPackage.setSaveManifest(false);
		jarPackage.setGenerateManifest(false);
		jarPackage.setReuseManifest(false);

		// TODO Outsource, check if this project is even a Java project
		final IJavaProject jproject = JavaCore.create(getProject());
		final IJavaProject[] elements = new IJavaProject[1];
		elements[0] = jproject;
		jarPackage.setElements(elements);
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
			tmpFile = File.createTempFile(BUILDER_ID + "-", ".jar");
		} catch (IOException e) {
			final String errorMessage = BUILDER_ID + ": " + getProject().getName() + " could not create temporary jar file!";
			final CoreException coreException = JarPackagerUtil.createCoreException(errorMessage, e);
			// Rethrow
			throw coreException;
		}
		final IPath jarPath = Path.fromOSString(tmpFile.getAbsolutePath());

		jarPackage.setJarLocation(jarPath);

		final Shell shell = getShell();
		final JarFileExportOperation jarFileExportOperation = new JarFileExportOperation(jarPackage, shell);
		try {
			jarFileExportOperation.run(monitor);
		} catch (InvocationTargetException e) {
			final String errorMessage = BUILDER_ID + ": " + getProject().getName() + " jar file export failed!";
			final CoreException coreException = JarPackagerUtil.createCoreException(errorMessage, e);
			// Rethrow
			throw coreException;
		} catch (InterruptedException e) {
			// Restore interrupted state
			Thread.currentThread().interrupt();
			final String errorMessage = BUILDER_ID + ": " + getProject().getName() + " jar file export interrupted!";
			final CoreException coreException = JarPackagerUtil.createCoreException(errorMessage, e);
			// Rethrow
			throw coreException;
		}
		return jarPackage.getJarLocation().toFile();
	}

	private void copyJarFileIntoBndWorkspaceRepository(final File jarFile) throws CoreException {
		// JAR file should have been created, copy it into the bndtools workspace repo
		final Optional<RepositoryPlugin> bndWorkspaceRepository = getBndWorkspaceRepository();
		if (!bndWorkspaceRepository.isPresent()) {
			final String errorMessage = BUILDER_ID + ": " + getProject().getName() + "bnd workspace directory could not be determined!";
			final CoreException coreException = JarPackagerUtil.createCoreException(errorMessage, new UnsupportedOperationException(errorMessage));
			// Rethrow
			throw coreException;
		}
		try (final InputStream jarFileInputStream = new FileInputStream(jarFile)) {
			System.out.println(String.format("Copying file %s into %s", jarFile.getAbsolutePath(), bndWorkspaceRepository.get().getLocation()));
			try {
				putFileToBndRepository(jarFileInputStream, bndWorkspaceRepository.get());
			} catch (Exception e) {
				final String errorMessage = BUILDER_ID + ": " + getProject().getName() + " could not copy jar bundle file " + jarFile.getAbsolutePath() + " into bnd workspace repository " + bndWorkspaceRepository.get().getLocation() + "!";
				final CoreException coreException = JarPackagerUtil.createCoreException(errorMessage, e);
				// Rethrow
				throw coreException;
			}
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

	/**
	 * Returns the active shell.
	 * 
	 * @return the active shell
	 */
	private Shell getShell() {
		return JavaPlugin.getActiveWorkbenchShell();
	}

	/**
	 * Get the manifest from project.
	 * 
	 * TODO Consider fallback to search for MANIFEST.MF in the build output folder,
	 * as it may have been generated
	 * 
	 * @return optional containing manifest location or empty optional if not found
	 */
	private Optional<IPath> getManifestLocation() {
		final IResource manifestFileResource = getProject().findMember("META-INF/MANIFEST.MF");
		if (manifestFileResource == null || !manifestFileResource.exists()) {
			// Skip or abort builder, as it will not be a valid JAR file without MANIFEST
			return Optional.empty();
		}
		System.out.println("MANIFEST.MF found @ " + manifestFileResource.getProjectRelativePath());
		return Optional.ofNullable(manifestFileResource.getFullPath());
	}

	private Optional<RepositoryPlugin> getBndWorkspaceRepository() throws CoreException {
		final File bndWorkspaceDirectory = getBndWorkspaceDirectory();
		if (bndWorkspaceDirectory == null) {
			System.err.println("bnd workspace directory could not be determined!");
			return Optional.empty();
		}
		Workspace bndWorkspace;
		try {
			bndWorkspace = Workspace.getWorkspace(bndWorkspaceDirectory);
			if (bndWorkspace == null) {
				System.err.println("bnd workspace could not be retrieved!");
				return Optional.empty();
			}
			// Prerequisite: bnd workspace repository with well-defined name
			// BND_WORKSPACE_REPO_NAME must be present
			return Optional.ofNullable(bndWorkspace.getRepository(BND_WORKSPACE_REPO_NAME));
		} catch (Exception e) {
			final String errorMessage = BUILDER_ID + ": " + getProject().getName() + " could not obtain bnd workspace repository!";
			final CoreException coreException = JarPackagerUtil.createCoreException(errorMessage, e);
			// Rethrow
			throw coreException;
		}
	}

	private PutResult putFileToBndRepository(final InputStream inputStream, final RepositoryPlugin repository) throws Exception {
		return repository.put(inputStream, null);
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
	 * @return <code>true</code> if the builder order is correct, and
	 *         <code>false</code> otherwise
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

	protected void startupOnInitialize() {
		// add builder init logic here
	}

	protected void clean(IProgressMonitor monitor) {
		// add builder clean logic here
	}

}
