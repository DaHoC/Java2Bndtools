package com.qivicon.testbuilder;

import java.io.IOException;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

public class ResourceBuildVisitor implements IResourceVisitor {

	private JarZipper jarZipper;
	private IPath javaBuilderOutputFolder;

	// TODO Pass the output folder to the builder, that's what we want to zip
	public ResourceBuildVisitor(final JarZipper jarZipper, final IPath javaBuilderOutputFolder) {
		this.jarZipper = jarZipper;
		this.javaBuilderOutputFolder = javaBuilderOutputFolder;
	}

	@Override
	public boolean visit(final IResource res) throws CoreException {
		// build the specified resource
		// return true to continue visiting children
//		URI fileURI = res.getLocationURI();
//		final File fileName = new File(fileURI);
//		final File fileName = res.getProjectRelativePath().toFile();
//		final File fileName = javaBuilderOutputFolder;
/*
		System.out.println(String.format("QIVICONBUILDER: Full builder for %s %s", res.getFullPath().toOSString(),
				Arrays.asList(fileName.list()).toString()));
*/
/*
		if (res.getType() == IResource.FILE) {
			IFile file = (IFile) res;
			String ext = res.getFileExtension();
		}
*/
		/* NOTE: Do not use the java builder output path directly, it is not written at the time this builder is invoked,
		 * although I assert that this builder is called AFTER the java builder
		 */
/*
		if (javaBuilderOutputFolder == null) {
			System.err.println(QiviconBuilder.BUILDER_ID + ": Java builder output folder is null!");
			return false;
		}
		if (!javaBuilderOutputFolder.isEmpty()) {
			System.err.println(QiviconBuilder.BUILDER_ID + ": Java builder output folder is empty!");
			return false;
		}
		File javaBuilderOutputFolderFile = javaBuilderOutputFolder.toFile();
		if (javaBuilderOutputFolderFile == null || !javaBuilderOutputFolderFile.exists()) {
			System.err.println(QiviconBuilder.BUILDER_ID + ": Java builder output folder does not exist!");
			return false;
		}
		if (!javaBuilderOutputFolderFile.isDirectory() || !javaBuilderOutputFolderFile.canRead()) {
			System.err.println(QiviconBuilder.BUILDER_ID + ": Java builder output folder cannot be read!");
			return false;
		}
*/

//		for (final File builtFile : javaBuilderOutputFolderFile.listFiles()) {
		// TODO Filter for files inside the java builder output folder - this can be done better I guess
		final IPath javaOutputFolderRelativePathToProjectWithoutProjectNameItself = javaBuilderOutputFolder.makeRelativeTo(res.getProject().getProjectRelativePath()).removeFirstSegments(1);
		System.out.println("Checking " + res.getProjectRelativePath() + " against " + javaOutputFolderRelativePathToProjectWithoutProjectNameItself.toString());
		if (!javaOutputFolderRelativePathToProjectWithoutProjectNameItself.isPrefixOf(res.getProjectRelativePath())) {
			return true;
		}
		try {
			System.err.println("Adding file " + res.getName() + " = " + res.getFullPath() + " -/- " + res.getProjectRelativePath() + " JAVA outdir=" + javaBuilderOutputFolder.toString());
			// TODO Add path relative to project directory, do not put full path into zip
			jarZipper.add(res);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
//		}
		return true;
	}
}
