package com.qivicon.testbuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.eclipse.core.commands.operations.OperationStatus;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceVisitor;
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
import org.eclipse.jdt.internal.ui.jarpackager.JarBuilder;
import org.eclipse.jdt.internal.ui.jarpackager.JarFileExportOperation;
import org.eclipse.jdt.internal.ui.jarpackager.JarPackagerUtil;
import org.eclipse.jdt.internal.ui.jarpackager.ManifestProvider;
import org.eclipse.jdt.internal.ui.jarpackager.PlainJarBuilder;
import org.eclipse.jdt.ui.jarpackager.IJarBuilder;
import org.eclipse.jdt.ui.jarpackager.IManifestProvider;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import aQute.bnd.build.Workspace;
import aQute.bnd.service.RepositoryListenerPlugin;
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
	// TODO Use temporary folder using File.createTemp...
	static final String BUILDER_OUTPUTPATH = "qiviconBuild";
	static final String BUILDER_OUTPUTFILE = "com.acme.dummy.jar";
	/** The well-defined name of the bnd workspace repository that the artifacts are deployed to if the repo is present.
	 * "Local" is a repository created by default, but may be missing if user decided to remove it
	 */
	static final String BND_WORKSPACE_REPO_NAME = "Local";
	File targetArchiveFile;

	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
		// kind is one of FULL_BUILD, INCREMENTAL_BUILD, AUTO_BUILD, CLEAN_BUILD
		switch (kind) {
		default:
		case IncrementalProjectBuilder.FULL_BUILD:
		case IncrementalProjectBuilder.CLEAN_BUILD:
			// TODO Should CLEAN_BUILD remove our builder output, i.e. is it the clean operation or the build after clean?
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
		// the visitor does the work.
		delta.accept(new ResourceBuildDeltaVisitor());
	}

	protected void fullBuild(final IProgressMonitor monitor) throws CoreException {
		// TODO We can automatically swap the builders when the order is wrong, i.e. fix this on-the-fly
		boolean builderOrderOK = checkBuilderOrdering();
		if (!builderOrderOK) {
			System.err.println(BUILDER_ID + ": Bad builder order for project " + getProject().getName());
		}

		/*
		 * Obtain the output/target folder (only for Java projects, we do not want to
		 * have plain groovy projects?)
		 */
		final Optional<IPath> outputFolderOpt = retrieveOutputFolder();
		if (!outputFolderOpt.isPresent()) {
			final String errorMessage = BUILDER_ID
					+ ": OutputPath is not present (maybe the Java project was not yet built), aborting build";
			final IStatus status = new OperationStatus(4, QiviconBuilder.BUILDER_ID, -1, errorMessage,
					new Throwable(errorMessage));
			throw new CoreException(status);
		}
		final IPath javaBuilderOutputFolder = outputFolderOpt.get();
		
		// TODO Do not make this absemmeln, but rather skip this project or display/log a nice hint that the builder could not proceed
		final Optional<IPath> manifestLocationOpt = getManifestLocation();
		if (!manifestLocationOpt.isPresent()) {
			final String errorMessage = BUILDER_ID + ": " + getProject().getName() + " MANIFEST.MF file not found, aborting build!";
			final IStatus status = new OperationStatus(4, QiviconBuilder.BUILDER_ID, -1, errorMessage, new Throwable(errorMessage));
			throw new CoreException(status);
		}

		System.err.println("Manifest full path: " + manifestLocationOpt.get().toString());
		// Reuse and invoke the internal Eclipse JAR exporter
		// jarPackage an object containing all required information to make an export
		final JarPackageData jarPackage = new JarPackageData();
		// TODO Check if I explicitly need to add the Manifest
		jarPackage.setManifestLocation(manifestLocationOpt.get());
		IFile manifestFile = jarPackage.getManifestFile();
		boolean manifestFileExists = manifestFile.exists();
/*
		final IManifestProvider manifestProvider = new ManifestProvider();
		final Manifest manifest = manifestProvider.create(jarPackage);
		jarPackage.setManifestProvider(manifestProvider);
*/
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
		
		// TODO Change the location
//		final IPath jarPath = getProject().getProjectRelativePath().append("tmp.jar");
		IPath jarPath = Path.fromOSString("/home/janhendriks/projects/bnd/qivicon-develop/runtime-EclipseApplication/TestForeignproject/tmp.jar");
		if (jarPath.getFileExtension() == null) {
			jarPath = jarPath.addFileExtension("jar");
		}
		
		jarPackage.setJarLocation(jarPath);
		try {
			jarPath.toFile().getAbsoluteFile().createNewFile();
		} catch (IOException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
		final Shell shell = getShell();
		final JarFileExportOperation jarFileExportOperation = new JarFileExportOperation(jarPackage, shell);
		try {
			jarFileExportOperation.run(monitor);
		} catch (InvocationTargetException | InterruptedException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
//		IPath destinationPath = getProject().getFullPath().removeFirstSegments(leadingSegmentsToRemove);
//		jarBuilder.writeFile((IFile) resource, destinationPath);

/*
		jarPath.toFile().delete();
		
		System.err.println("JAR created @ " + jarPackage.getJarLocation());
		// Reminder for convenience methods
		JarPackagerUtil.getMetaEntry();
		
//		IJarBuilder jarBuilder = jarPackage.createPlainJarBuilder();
		IJarBuilder jarBuilder = jarPackage.getJarBuilder();
		jarBuilder.open(jarPackage, shell, null);

		final IFile targetFile = getProject().getFile(jarPath);
/*
//		File tempFile;
			IPath tmpEclFile = getProject().getProjectRelativePath();
//			tempFile = File.createTempFile(BUILDER_ID + "-", ".jar", new File(getProject().getLocationURI()));
//			System.err.println("TEMP file created @ " + tempFile.getAbsolutePath());
			final IFile targetFile = getProject().getFile(tmpEclFile.append("temp.jar"));
			try {
				new File(targetFile.getLocationURI()).createNewFile();
			} catch (IOException e2) {
				// TODO Auto-generated catch block
				e2.printStackTrace();
			}
*/
			// Add resource to Jar file
//			jarBuilder.writeFile(targetFile, getProject().getProjectRelativePath().append("src"));

//		jarBuilder.close();

/*
		try (final ZipFile z2 = JarPackagerUtil.getArchiveFile(jarPackage.getJarLocation())) {
			jarBuilder.writeArchive(z2, monitor);
		} catch (IOException e3) {
			// TODO Auto-generated catch block
			e3.printStackTrace();
		}
*/
/*
		ZipFile zipFile;
		try {
			final File tempFile = File.createTempFile(BUILDER_ID + "-", ".jar");
			System.err.println("ZIP created @ " + tempFile.getAbsolutePath());
			zipFile = new ZipFile(jarPackage.getJarLocation().toFile());
			zipFile = new ZipFile(tempFile);
			jarBuilder.writeArchive(zipFile, monitor);
		} catch (ZipException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		} catch (IOException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
*/		
		
		// JAR file should have been created, copy it into the bndtools workspace repo
		final Optional<RepositoryPlugin> bndWorkspaceRepository = getBndWorkspaceRepository();
		if (!bndWorkspaceRepository.isPresent()) {
			return;
		}
		try (final InputStream jarFileInputStream = new FileInputStream(jarPackage.getJarLocation().toFile())) {
			try {
				System.err.println(String.format("Copying file %s into %s", jarPackage.getJarLocation().toString(), bndWorkspaceRepository.get().getLocation()));

				putFileToBndRepository(jarFileInputStream, bndWorkspaceRepository.get());
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
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
	 * Returns the active shell
	 * @return the active shell
	 */
	protected Shell getShell() {
		return JavaPlugin.getActiveWorkbenchShell();
	}

	// 
	/**
	 * Get the manifest from project.
	 * TODO Consider fallback to search for MANIFEST.MF in the build output folder, as it may have been generated
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

	private IFolder getQiviconBuilderTargetFolder() {
		// According to the API docs, this never returns null
		return getProject().getFolder(BUILDER_OUTPUTPATH);
	}

	/**
	 * Obtain the path of the Java builder output folder, or an empty optional if
	 * the Java builder has not built this project before.
	 * 
	 * TODO: There may be more than one output dir, this is only the default:
	 * TODO: See https://stackoverflow.com/questions/32188028/determining-if-an-ifolder-is-a-java-output-folder?noredirect=1&lq=1
	 * 
	 * @return optional path of the java project builder output folder, or empty
	 *         optional
	 * @throws CoreException if the project is not a Java project (anymore)
	 */
	private Optional<IPath> retrieveOutputFolder() throws CoreException {
		final IJavaProject jproject = JavaCore.create(getProject());
		if (jproject == null) {
			// not a java project (anymore?)
			final String errorMessage = BUILDER_ID + ": Not a Java project (anymore), required for output folder";
			final IStatus status = new OperationStatus(4, QiviconBuilder.BUILDER_ID, -1, errorMessage, new Throwable(errorMessage));
			throw new CoreException(status);
		}

		final IPath currentOutputPath = jproject.getOutputLocation();
		return Optional.ofNullable(currentOutputPath);
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
			// Prerequisite: bnd workspace repository with well-defined name BND_WORKSPACE_REPO_NAME must be present
			return Optional.ofNullable(bndWorkspace.getRepository(BND_WORKSPACE_REPO_NAME));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return Optional.empty();
	}

	private PutResult putFileToBndRepository(final InputStream inputStream, final RepositoryPlugin repository) throws Exception {
		return repository.put(inputStream, null);
		// TODO If refresh is not done automatically, use RepositoryListenerPlugin.bundleAdded
//		bndWorkspace.getPlugins(RepositoryListenerPlugin.class).bundleAdded(repository, jar, file);
	}

	private static File getBndWorkspaceDirectory() throws CoreException {
		IWorkspaceRoot eclipseWorkspace = ResourcesPlugin.getWorkspace().getRoot();
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
		ICommand[] cs = getProject().getDescription().getBuildSpec();
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
