package com.qivicon.testbuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.commands.operations.OperationStatus;
import org.eclipse.core.resources.ICommand;
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
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

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

		try (final JarZipper jarZipper = setupBuildTarget()) {
			// TODO Do we need this visitor? Or do we simply zip the target folder w/o
			// visitor?
			final IResourceVisitor resourceVisitor = new ResourceBuildVisitor(jarZipper, javaBuilderOutputFolder);
			getProject().accept(resourceVisitor);
		} catch (CoreException e) {
			// Rethrow this
			throw e;
		} catch (IOException e) {
			// Can occur when file operations (reading, writing to JAR file fails)
			System.err.println(BUILDER_ID + ": " + e.getMessage());
			e.printStackTrace();
		} catch (Exception e) {
			// Can occur when .close() operation on jarZipper fails
			System.err.println(BUILDER_ID + ": " + e.getMessage());
			e.printStackTrace();
		}
		// JAR file should have been created, copy it into the bndtools workspace repo
		final Optional<RepositoryPlugin> bndWorkspaceRepository = getBndWorkspaceRepository();
		if (!bndWorkspaceRepository.isPresent()) {
			return;
		}
		try (final InputStream jarFileInputStream = new FileInputStream(targetArchiveFile)) {
			try {
				System.err.println(String.format("Copying file %s into %s", targetArchiveFile.getPath(), bndWorkspaceRepository.get().getLocation()));

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

	private JarZipper setupBuildTarget() throws IOException, CoreException {
		// Get the manifest from project
//		final URI projectLocation = getProject().getLocationURI();
//		File manifestFile = new File(projectLocation.getPath() + "/META-INF/MANIFEST.MF");
		final IResource manifestFileResource = getProject().findMember("META-INF/MANIFEST.MF");
		// TODO Consider fallback to search for MANIFEST.MF in the build output folder,
		// it may have been generated
		if (manifestFileResource == null) {
			// Abort builder, as it will not be a valid JAR file without MANIFEST
			// TODO Do not make this absemmeln, but rather a nice hint that the builder could not proceed
			final String errorMessage = BUILDER_ID + ": " + getProject().getName() + " MANIFEST.MF file not found, aborting build!";
			final IStatus status = new OperationStatus(4, QiviconBuilder.BUILDER_ID, -1, errorMessage,
					new Throwable(errorMessage));
			throw new CoreException(status);
		}
		System.out.println("MANIFEST.MF found @ " + manifestFileResource.getProjectRelativePath());
//		final File manifestFile = new File(manifestFileResource.getLocationURI());
		final IFolder qiviconBuilderTargetFolder = getQiviconBuilderTargetFolder();
		if (!qiviconBuilderTargetFolder.exists()) {
			qiviconBuilderTargetFolder.getLocation().toFile().mkdirs();
		}
		final File targetQiviconBuilderOutputFolder = new File(qiviconBuilderTargetFolder.getLocationURI());
		if (!targetQiviconBuilderOutputFolder.exists()) {
			targetQiviconBuilderOutputFolder.mkdirs();
		}
		targetArchiveFile = new File(targetQiviconBuilderOutputFolder.getPath() + "/" + BUILDER_OUTPUTFILE);
		if (!targetArchiveFile.exists()) {
			System.err.println(BUILDER_ID + ": Target JAR file not found, (re)creating it!");
//			archiveFile.mkdirs();
			targetArchiveFile.createNewFile();
		} else {
			// TODO Even if it exists, delete it beforehand when a full built is done
//			archiveFile.delete();
		}
		return new JarZipper(targetArchiveFile, manifestFileResource);
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
