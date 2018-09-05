package com.qivicon.testbuilder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
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
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

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
		final Optional<IPath> outputFolderOpt = obtainOutputFolder();
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
	}

	private JarZipper setupBuildTarget() throws IOException, CoreException {
		// Get the manifest from project
		final URI projectLocation = getProject().getLocationURI();
//		File manifestFile = new File(projectLocation.getPath() + "/META-INF/MANIFEST.MF");
		final IResource manifestFileResource = getProject().findMember("META-INF/MANIFEST.MF");
		// TODO Consider fallback to search for MANIFEST.MF in the build output folder,
		// it may have been generated
		if (manifestFileResource == null) {
			// Abort builder, as it will not be a valid JAR file without MANIFEST
			final String errorMessage = BUILDER_ID + ": Manifest file not found, aborting build!";
			final IStatus status = new OperationStatus(4, QiviconBuilder.BUILDER_ID, -1, errorMessage,
					new Throwable(errorMessage));
			throw new CoreException(status);
		}
		System.out.println("MANIFEST.MF found @ " + manifestFileResource.getProjectRelativePath());
		final File manifestFile = new File(manifestFileResource.getLocationURI());
		/*
		 * final IFolder qiviconBuilderTargetFolder = getQiviconBuilderTargetFolder();
		 * if (qiviconBuilderTargetFolder == null ||
		 * !qiviconBuilderTargetFolder.exists()) { // ...
		 * qiviconBuilderTargetFolder.getLocation().toFile().mkdirs(); }
		 */
		final File targetQiviconBuilderOutputFolder = new File(projectLocation.getPath() + "/qiviconBuild/");
		if (!targetQiviconBuilderOutputFolder.exists()) {
			targetQiviconBuilderOutputFolder.mkdirs();
		}
		final File targetArchiveFile = new File(targetQiviconBuilderOutputFolder.getPath() + "/test.jar");
		if (!targetArchiveFile.exists()) {
			System.err.println(BUILDER_ID + ": Target Jar file not found, (re)creating it!");
//			archiveFile.mkdirs();
			targetArchiveFile.createNewFile();
		} else {
			// TODO Even if it exists, delete it beforehand when a full built is done
//			archiveFile.delete();
		}
		return new JarZipper(targetArchiveFile, manifestFile);
	}

	private IFolder getQiviconBuilderTargetFolder() {
		return getProject().getFolder("qiviconBuild");
	}

	/**
	 * Obtain the path of the Java builder output folder, or an empty optional if
	 * the Java builder has not built this project before.
	 * 
	 * @return optional path of the java project builder output folder, or empty
	 *         optional
	 * @throws CoreException if the project is not a Java project (anymore)
	 */
	private Optional<IPath> obtainOutputFolder() throws CoreException {
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
