package com.qivicon.bndbuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.jarpackager.IJarExportRunnable;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;
import org.eclipse.ui.console.MessageConsoleStream;

import aQute.bnd.build.Workspace;
import aQute.bnd.service.RepositoryPlugin;

/**
 * Qivicon bnd builder for zipping compiled build artifacts of an arbitrary Java
 * project into an JAR bundle file and copy it to a well-defined bnd workspace
 * repository location.
 */
public final class QiviconBuilder extends IncrementalProjectBuilder {

	static final String BUILDER_ID = QiviconBuilderUtils.CORE_PLUGIN_ID + ".qiviconbndbuilder";
	static final String BUILDER_NAME = "QIVICON bnd builder";

	private static final String BUILDER_PROPERTIES_LOCATION = "META-INF/plugin.properties";

	/**
	 * The well-defined name of the bnd workspace repository that the artifacts are
	 * deployed to if the repo is present. "Local" is a repository created by
	 * default, but may be missing if user decided to remove it
	 */
	private String bndWorkspaceRepositoryName = "Local";

	private MessageConsoleStream consoleStream;
	// Folders to exclude from export (case-sensitive)
	private Collection<String> excludeFolders;
	// Files to exclude from export (case-sensitive)
	private Collection<String> excludeFiles;

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

	protected void incrementalBuild(final IResourceDelta delta, final IProgressMonitor monitor) {
		// TODO Implement me
		log(String.format("%s: Delta changes of %s (nothing done at the moment)", BUILDER_ID, delta.getFullPath().toString()));
	}

	protected void fullBuild(final IProgressMonitor monitor) throws CoreException {
		QiviconBuilderUtils.checkBuilderOrdering(getProject());
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
		final Optional<IPath> manifestLocationOpt = QiviconBuilderUtils.getManifestLocation(getProject());
		if (!manifestLocationOpt.isPresent()) {
			final String errorMessage = String.format("%s project %s: %s file not found, skipping project!", BUILDER_ID, getProject().getName(), QiviconBuilderUtils.MANIFEST_LOCATION);
			log(errorMessage);
			return Optional.empty();
		}

		readFilesAndFoldersToExclude();

		final Object[] exportElements = retrieveSelectedElementsToExport();
		if (exportElements == null || exportElements.length == 0) {
			log(String.format("%s project %s: Nothing to export", BUILDER_ID, getProject().getName()));
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
		jarPackage.setElements(exportElements);
		jarPackage.setExportClassFiles(true);
		jarPackage.setExportOutputFolders(true);
		jarPackage.setExportJavaFiles(true);
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
			throw QiviconBuilderUtils.createCoreException(errorMessage, e);
		}
		final IPath jarPath = Path.fromOSString(tmpFile.getAbsolutePath());

		jarPackage.setJarLocation(jarPath);

		final IJarExportRunnable jarFileExportOperation = jarPackage.createJarExportRunnable(null);
		try {
			jarFileExportOperation.run(monitor);
		} catch (InvocationTargetException e) {
			final String errorMessage = String.format("%s project %s: jar file export failed!", BUILDER_ID, getProject().getName());
			throw QiviconBuilderUtils.createCoreException(errorMessage, e);
		} catch (InterruptedException e) {
			// Restore interrupted state
			Thread.currentThread().interrupt();
			final String errorMessage = String.format("%s project %s: jar file export interrupted!", BUILDER_ID, getProject().getName());
			throw QiviconBuilderUtils.createCoreException(errorMessage, e);
		}
		return Optional.of(jarPackage.getJarLocation().toFile());
	}

	/**
	 * Filters over all project resources and excludes files and directories
	 * mentioned in {@link #EXCLUDE_FILES} and {@link #EXCLUDE_DIRECTORIES}
	 * respectively. Iteration is internally split up by java resources and non-java
	 * resources. According to Eclipse source, the returned elements must be (a mix)
	 * of type IJavaProject, IJavaElement, IResource, IFile, ..?
	 * 
	 * @return elements of type IJavaProject, IJavaElement, IResource, IFile,...
	 * @throws JavaModelException by the call to get java or non-java project resources
	 */
	private Object[] retrieveSelectedElementsToExport() throws JavaModelException {
		final Optional<IJavaProject> jprojectOpt = retrieveJavaProject();
		if (!jprojectOpt.isPresent()) {
			return new Object[0];
		}

		final Collection<Object> selectedElements = new LinkedHashSet<>();
		// Gather java-specific resources
		final Collection<Object> javaResources = collectJavaResources(jprojectOpt.get());
		selectedElements.addAll(javaResources);

		// Gather non-java specific resources
		final Collection<Object> nonJavaResources = collectNonJavaResources(jprojectOpt.get());
		selectedElements.addAll(nonJavaResources);

		return selectedElements.toArray();
	}

	private Optional<IJavaProject> retrieveJavaProject() {
		// Check if this project is even a Java project
		IJavaProject jproject = null;
		try {
			if (getProject().hasNature(JavaCore.NATURE_ID)) {
				jproject = JavaCore.create(getProject());
			}
		} catch (CoreException ex) {
			final String errorMessage = String.format("%s cannot obtain nature of project %s: %s!", BUILDER_ID, getProject().getName(), ex.getMessage());
			log(errorMessage);
			return Optional.empty();
		}

		if (jproject == null || !jproject.exists()) {
			final String errorMessage = String.format("%s project %s is not a Java project (anymore)!", BUILDER_ID, getProject().getName());
			log(errorMessage);
			return Optional.empty();
		}
		return Optional.ofNullable(jproject);
	}

	private Collection<Object> collectJavaResources(final IJavaProject jproject) throws JavaModelException {
		final IJavaElement[] projectChildren = jproject.getChildren();
		if (projectChildren == null) {
			return Collections.emptySet();
		}
		final Collection<Object> selectedElements = new LinkedHashSet<>(projectChildren.length);
		for (final IJavaElement javaElement : projectChildren) {
			log(String.format("Project java element %s encountered", javaElement.getElementName()));
			selectedElements.add(javaElement);
		}
		return selectedElements;
	}

	private Collection<Object> collectNonJavaResources(final IJavaProject jproject) throws JavaModelException {
		final Object[] projectNonJavaChildren = jproject.getNonJavaResources();
		if (projectNonJavaChildren == null) {
			return Collections.emptySet();
		}
		final Collection<Object> selectedElements = new LinkedHashSet<>(projectNonJavaChildren.length);
		for (final Object nonJavaElement : projectNonJavaChildren) {
			if (nonJavaElement instanceof IFile) {
				final IFile nonJavaFile = (IFile) nonJavaElement;
				log(String.format("Project non-java file %s encountered", nonJavaFile.getName()));
				if (!excludeFiles.contains(nonJavaFile.getName())) {
					selectedElements.add(nonJavaFile);
				}
			} else if (nonJavaElement instanceof IFolder) {
				final IFolder nonJavaFolder = (IFolder) nonJavaElement;
				log(String.format("Project non-java folder %s encountered", nonJavaFolder.getName()));
				if (!excludeFolders.contains(nonJavaFolder.getName())) {
					selectedElements.add(nonJavaFolder);
				}
			} else {
				// We do not know what type it is and we do not actually care, just take it
				selectedElements.add(nonJavaElement);
			}
		}
		return selectedElements;
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
			throw QiviconBuilderUtils.createCoreException(errorMessage, e);
		} catch (Exception e) {
			final String errorMessage = String.format("%s project %s: Could not copy jar bundle file %s into bnd workspace repository %s!", BUILDER_ID, getProject().getName(), jarFile.getAbsolutePath(), bndWorkspaceRepository.get().getLocation());
			throw QiviconBuilderUtils.createCoreException(errorMessage, e);
		}
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
			// Prerequisite: bnd workspace repository with well-defined name must be present
			final RepositoryPlugin bndWorkspaceRepository = bndWorkspace.getRepository(bndWorkspaceRepositoryName);
			if (bndWorkspaceRepository == null) {
				log(String.format("%s project %s: bnd workspace repository '%s' could not be retrieved!", BUILDER_ID, getProject().getName(), bndWorkspaceRepositoryName));
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

	private void readFilesAndFoldersToExclude() throws CoreException {
		if (this.excludeFolders != null && this.excludeFiles != null) {
			return;
		}
		final Properties properties = new Properties();

		try (final InputStream input = getClass().getClassLoader().getResourceAsStream(BUILDER_PROPERTIES_LOCATION)) {
			properties.load(input);
			final String excludeFoldersProperty = properties.getProperty("exclude_folders");
			final String excludeFilesProperty = properties.getProperty("exclude_files");
			final String bndWorkspaceRepositoryProperty = properties.getProperty("bnd_workspace_repository");
			if (excludeFoldersProperty == null || excludeFilesProperty == null
					|| bndWorkspaceRepositoryProperty == null) {
				// Wrap into CoreException
				final String message = String.format("Could not read properties file %s values!", BUILDER_PROPERTIES_LOCATION);
				throw QiviconBuilderUtils.createCoreException(message, null);
			}
			this.bndWorkspaceRepositoryName = bndWorkspaceRepositoryProperty.trim();
			log(String.format("Bnd workspace repository to use=%s", this.bndWorkspaceRepositoryName));
			final String[] excludeFoldersArr = excludeFoldersProperty.split(",");
			final String[] excludeFilesArr = excludeFilesProperty.split(",");
			log("Exclude folders=" + Arrays.toString(excludeFoldersArr));
			log("Exclude files=" + Arrays.toString(excludeFilesArr));
			this.excludeFolders = Arrays.asList(excludeFoldersArr);
			this.excludeFiles = Arrays.asList(excludeFilesArr);
		} catch (IOException e) {
			// Wrap into CoreException
			final String message = String.format("Could not read properties file %s!", BUILDER_PROPERTIES_LOCATION);
			throw QiviconBuilderUtils.createCoreException(message, e);
		}
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
		if (!QiviconBuilderUtils.DEBUG_OUTPUT) {
			return;
		}
		if (this.consoleStream == null) {
			this.consoleStream = QiviconBuilderUtils.getStreamForLoggingToEclipseConsole(BUILDER_NAME + " console");
			this.consoleStream.setActivateOnWrite(true);
		}
		this.consoleStream.println(message);
	}

}
