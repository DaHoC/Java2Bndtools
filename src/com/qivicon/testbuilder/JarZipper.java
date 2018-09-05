package com.qivicon.testbuilder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.core.resources.IResource;

public class JarZipper implements AutoCloseable {

	public static int BUFFER_SIZE = 10240;

	private JarOutputStream targetArchive;

	public JarZipper(final File archiveFile, final File manifestFile) throws IOException {
		System.out.println(String.format("QIVICONBUILDER: Instantiating JarZipper with %s and %s", archiveFile.getAbsolutePath(), manifestFile.getAbsolutePath()));

		// TODO Manifest content is still missing, there's an error somewhere
		final Manifest manifest = new Manifest(new FileInputStream(manifestFile));
//		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		if (!archiveFile.exists()) {
			System.err.println("QIVICONBUILDER: Target Jar file not found, (re)creating it!");
			archiveFile.createNewFile();
		}
		this.targetArchive = new JarOutputStream(new FileOutputStream(archiveFile), manifest);
//		add(new File("inputDirectory"), targetArchive);
	}

	public void add(final IResource source) throws IOException {
		if (source.getType() == IResource.FOLDER) {
			addDirectory(source);
		}
		if (source.getType() == IResource.FILE) {
			if (source.getProjectRelativePath().toString().equals("META-INF/MANIFEST.MF")) {
				System.err.println("Skipping MANIFEST as it is already included!");
				return;
			}
			addFile(source);
		}
	}

	private void addDirectory(final IResource source) throws IOException {
		if (source == null || !source.exists() || source.getType() != IResource.FOLDER) {
			return;
		}
		final String directoryName = convertPath(source.getProjectRelativePath().toString(), true);
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

	private void addFile(final IResource source) throws IOException {
		if (source == null || source.getLocationURI() == null || !source.exists() || source.getType() != IResource.FILE) {
			return;
		}
		final File fileToBeZipped = new File(source.getLocationURI());
		if (!fileToBeZipped.exists() || !fileToBeZipped.isFile() || !fileToBeZipped.canRead()) {
			return;
		}
		final String fileName = convertPath(source.getProjectRelativePath().toString(), false);
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
	 * To conform to ZIP standards, strip potential slash at the beginning, make sure slash is appended at the end of directories.
	 * @param pathName non-null path name of file or directory
	 * @return sanitized, conforming path
	 */
	private static String convertPath(final String pathName, boolean isDir) {
		String conformingName = pathName.replace("\\", "/");
		if (!conformingName.isEmpty()) {
			if (isDir && !conformingName.endsWith("/")) {
				conformingName += "/";
			}
			if (conformingName.startsWith("/")) {
				conformingName = conformingName.substring(1);
			}
		}
		return conformingName;
	}

	@Override
	public void close() throws Exception {
		targetArchive.close();
	}

}
