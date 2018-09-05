package com.qivicon.testbuilder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;

public class JarZipper implements AutoCloseable {

	private static final int BUFFER_SIZE = 10240;

	private JarOutputStream targetArchive;

	// TODO Replace with temp file
	public JarZipper(final File archiveFile, final IResource manifestFile) throws IOException {
		System.out.println(String.format("QIVICONBUILDER: Instantiating JarZipper with %s and %s", archiveFile.getAbsolutePath(), manifestFile.getLocationURI().toString()));

		if (!manifestFile.exists()) {
			System.err.println("MANIFEST file not found!");
		}
//		try (final InputStream manifestStream = new FileInputStream(manifestFile)) {
//		Manifest manifest;
//		try (final InputStream manifestStream = manifestFile.getLocationURI().toURL().openStream()) {
			// TODO Manifest content is still missing, there's an error somewhere
//			manifest = new Manifest(manifestStream);
//			final Manifest manifest = new Manifest(manifestFile.getLocationURI().toURL().openStream());
//			manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION.toString(), "1.0");
//			Attributes manifestAttributes = manifest.getMainAttributes();
//			System.err.println("MANIFEST.MF attributes=" + manifestAttributes.entrySet());
//			manifest.getEntries().put("Bundle-SymbolicName", "com.acme.dummy");
			if (!archiveFile.exists()) {
				System.err.println(QiviconBuilder.BUILDER_ID + ": Target Jar file not found, (re)creating it!");
				archiveFile.createNewFile();
			}
//			this.targetArchive = new JarOutputStream(new FileOutputStream(archiveFile), manifest);
			this.targetArchive = new JarOutputStream(new FileOutputStream(archiveFile));
			addFile(manifestFile, null);
//		}
	}

	public void add(final IResource source, final IPath relativePath) throws IOException {
		if (source.getType() == IResource.FOLDER) {
			addDirectory(source, relativePath);
		}
		if (source.getType() == IResource.FILE) {
			addFile(source, relativePath);
		}
	}

	private void addDirectory(final IResource source, final IPath relativePath) throws IOException {
		if (source == null || !source.exists() || source.getType() != IResource.FOLDER) {
			return;
		}
		// TODO Use the name without the relativePath, e.g. strip bin/ from the beginning
		final String directoryName = makePathConformToZip(source.getProjectRelativePath().toString(), relativePath, true);
		if (directoryName.isEmpty()) {
			return;
		}
		System.err.println("Adding directory " + source.getName() + " in " + source.getFullPath() + " -/- " + source.getProjectRelativePath());

		final JarEntry entry = new JarEntry(directoryName);
		entry.setTime(source.getModificationStamp());
		// TODO Check if already present, skip in this case (the builder may traverse the same file more than once due to some reason)
		targetArchive.putNextEntry(entry);
		targetArchive.closeEntry();
/*
		for (final File nestedFile : source.listFiles()) {
			add(nestedFile);
		}
*/
	}

	private void addFile(final IResource source, final IPath relativePath) throws IOException {
		if (source == null || source.getLocationURI() == null || !source.exists() || source.getType() != IResource.FILE) {
			return;
		}
		final File fileToBeZipped = new File(source.getLocationURI());
		if (!fileToBeZipped.exists() || !fileToBeZipped.isFile() || !fileToBeZipped.canRead()) {
			return;
		}
		final String fileName = makePathConformToZip(source.getProjectRelativePath().toString(), relativePath, false);
		if (fileName.isEmpty()) {
			return;
		}
		System.err.println("Adding file " + source.getName() + " in " + source.getFullPath() + " -/- " + source.getProjectRelativePath());

		final JarEntry entry = new JarEntry(fileName);
		entry.setTime(source.getModificationStamp());
		targetArchive.putNextEntry(entry);
		try (final BufferedInputStream in = new BufferedInputStream(new FileInputStream(fileToBeZipped))) {
			byte[] buffer = new byte[BUFFER_SIZE];
			while (true) {
				int count = in.read(buffer);
				if (count <= 0) {
					break;
				}
				targetArchive.write(buffer, 0, count);
			}
			targetArchive.closeEntry();
		}
	}

	/**
	 * To conform to ZIP standards:
	 * <ol>
	 * <li>Paths must use '/' slashes, not '\'</li>
	 * <li>Directory names must end with a '/' slash</li>
	 * <li>Entries may not begin with a '/' slash</li>
	 * <li>All JarEntry's names must NOT begin with a '/' slash</li>
	 * </ol>
	 * Besides this, move the path relative to the given relative path, such that e.g. if relativePath is bin,
	 * the pathName of bin/com/acme becomes com/acme
	 * 
	 * @param pathName non-null path name of file or directory
	 * @param relativePath path to strip at the beginning, may be null
	 * @param isDir pass <code>true</code> if pathName is of a directory, <code>false</code> otherwise
	 * @return sanitized, conforming path
	 */
	private static String makePathConformToZip(final String pathName, final IPath relativePath, boolean isDir) {
		String conformingName = pathName.replace("\\", "/");
		System.err.println("Input path=" + pathName);
		if (!conformingName.isEmpty()) {
			if (relativePath != null && conformingName.startsWith(relativePath.toString())) {
				conformingName = conformingName.substring(relativePath.toString().length());
			}
			if (isDir && !conformingName.endsWith("/")) {
				conformingName += "/";
			}
			if (conformingName.startsWith("/")) {
				conformingName = conformingName.substring(1);
			}
		}
		System.err.println("Output path=" + conformingName);
		return conformingName.trim();
	}

	@Override
	public void close() throws Exception {
		targetArchive.flush();
		targetArchive.close();
	}

}
