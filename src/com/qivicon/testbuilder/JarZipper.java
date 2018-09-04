package com.qivicon.testbuilder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class JarZipper implements AutoCloseable {

	public static int BUFFER_SIZE = 10240;

	private JarOutputStream targetArchive;

	public JarZipper(final File archiveFile, final File manifestFile) throws IOException {
		final Manifest manifest = new Manifest(new FileInputStream(manifestFile));
//		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		this.targetArchive = new JarOutputStream(new FileOutputStream(archiveFile), manifest);
//		add(new File("inputDirectory"), targetArchive);
	}

	public void add(final File source) throws IOException {
		if (source.isDirectory()) {
			addDirectory(source);
		} else {
			addFile(source);
		}
	}

	private void addDirectory(final File source) throws IOException {
		if (!source.isDirectory() || !source.exists()) {
			return;
		}
		final String directoryName = convertPath(source.getPath());
		final JarEntry entry = new JarEntry(directoryName);
		entry.setTime(source.lastModified());
		targetArchive.putNextEntry(entry);
		targetArchive.closeEntry();
		for (final File nestedFile : source.listFiles()) {
			add(nestedFile);
		}
	}

	private void addFile(final File source) throws IOException {
		if (source.isDirectory() || !source.exists()) {
			return;
		}
		final String fileName = convertPath(source.getPath());
		final JarEntry entry = new JarEntry(fileName);
		entry.setTime(source.lastModified());
		targetArchive.putNextEntry(entry);
		try (final BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
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
	 * To conform to ZIP standards, strip potential slash at the beginning, make sure slash is appended at the end.
	 * @param pathName non-null path name of file or directory
	 * @return sanitized, conforming path
	 */
	private static String convertPath(final String pathName) {
		String conformingName = pathName.replace("\\", "/");
		if (!conformingName.isEmpty()) {
			if (!conformingName.endsWith("/")) {
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
