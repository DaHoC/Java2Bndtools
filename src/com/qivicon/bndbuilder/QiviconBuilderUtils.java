package com.qivicon.bndbuilder;

import static java.util.Objects.requireNonNull;

import java.io.PrintStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.bndtools.api.BndtoolsConstants;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * Utilities class for the Qivicon bnd builder and nature containing static convenience methods to avoid code duplication.
 */
abstract class QiviconBuilderUtils {
	
	// Messages thrown as NPE when this is a null value (instead of e.g. an empty array which is valid)
	private static final String PROJECT_NULL_ERROR = "Project must not be null!";
	private static final String PROJECT_DESCRIPTION_NULL_ERROR = "Project description must not be null!";
	private static final String PROJECT_BUILDERS_NULL_ERROR = "Project builder commands must not be null!";
	private static final String PROJECT_NATURES_NULL_ERROR = "Project natures must not be null!";

	// Get from external bndtools API
	private static final String BND_NATURE_ID = BndtoolsConstants.NATURE_ID;
	private static final String BND_BUILDER_ID = BndtoolsConstants.BUILDER_ID;

	private static final int INTERNAL_ERROR = -10001;

	static final String CORE_PLUGIN_ID = "com.qivicon.bndbuilder";
	static final String MANIFEST_LOCATION = "META-INF/MANIFEST.MF";
	static final boolean DEBUG_OUTPUT = true;

	static final Consumer<IProject> ADD_QIVICONBNDBUILDER_NATURE = project -> {
		try {
			addQiviconBndBuilderNature(project);
		} catch (CoreException e) {
			// Ignore and skip this project and hide error, there's not much we can do here
		}
	};
	static final Consumer<IProject> REMOVE_QIVICONBNDBUILDER_NATURE = project -> {
		try {
			removeQiviconBndBuilderNature(project);
		} catch (CoreException e) {
			// Ignore and skip this project and hide error, there's not much we can do here
		}
	};

	private QiviconBuilderUtils() {
		throw new IllegalAccessError("Cannot instantiate QiviconBuilderUtils class");
	}

	/**
	 * Check if project has the Java nature but does not at the same time have the Bnd project nature assigned.
	 * Can be used to selectively add the QiviconBndBuilder (nature) to matching projects.
	 * 
	 * @param project project to check
	 * @return {@code true} if given project has a Java nature and not a Bnd nature assigned to it, {@code false} otherwise
	 */
	static final boolean isJavaProjectAndNotBndProject(final IProject project) {
		requireNonNull(project, PROJECT_NULL_ERROR);
		return (isJavaProject(project) && !isBndProject(project));
	}

	private static boolean isJavaProject(final IProject project) {
		return isMatchingProject(project, JavaCore.NATURE_ID, JavaCore.BUILDER_ID);
	}

	private static boolean isBndProject(final IProject project) {
		return isMatchingProject(project, BND_NATURE_ID, BND_BUILDER_ID);
	}

	/**
	 * Check if the given project has the QiviconBndBuilder project nature and corresponding builder assigned.
	 * 
	 * @param project project to check
	 * @return {@code true} if the project has the QiviconBndBuilder project nature and corresponding builder assigned to it, {@code false} otherwise
	 */
	static final boolean isQiviconBndBuilderProject(final IProject project) {
		return isMatchingProject(project, QiviconBuilderNature.NATURE_ID, QiviconBuilder.BUILDER_ID); 
	}

	private static boolean isMatchingProject(final IProject project, final String natureId, final String builderId) {
		requireNonNull(project, PROJECT_NULL_ERROR);
		try {
			final boolean givenProjectNaturePresent = project.hasNature(natureId);
			if (!givenProjectNaturePresent) {
				return false;
			}
			/* 
			 * Check also for corresponding Java builder org.eclipse.jdt.core.javabuilder to be present
			 * and at the same time check that the bnd builder bndtools.core.bndbuilder is not present
			 */
			final IProjectDescription desc = project.getDescription();
			if (desc == null) {
				return false;
			}
			final ICommand[] commands = desc.getBuildSpec();
			if (commands == null) {
				return false;
			}
			return Stream.of(commands).map(ICommand::getBuilderName).filter(Objects::nonNull).anyMatch(builderId::equals);
		} catch (CoreException e) {
			// Ignore and hide this exception, consider as non-matching project
		}
		return false;
	}

	/**
	 * Checks whether this builder is configured to run <b>after</b> the Java
	 * builder and automatically swap the builders on wrong ordering, i.e. fix this on-the-fly.
	 * 
	 * @param project the project which contains the builders to check
	 * @return {@code true} if the builder order is correct, {@code false} otherwise
	 * @exception CoreException if something goes wrong
	 */
	static final void checkBuilderOrdering(final IProject project) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final ICommand[] commands = description.getBuildSpec();
		requireNonNull(commands, PROJECT_BUILDERS_NULL_ERROR);
		int qiviconBuilderIndex = -1;
		int javaBuilderIndex = -1;
		// Determine builder positions from project's build specification
		for (int i = 0; i < commands.length; i++) {
			if (JavaCore.BUILDER_ID.equals(commands[i].getBuilderName())) {
				javaBuilderIndex = i;
			} else if (QiviconBuilder.BUILDER_ID.equals(commands[i].getBuilderName())) {
				qiviconBuilderIndex = i;
			}
			// Exit loop if both indices have already been found
			if (javaBuilderIndex != -1 && qiviconBuilderIndex != -1) {
				break;
			}
		}
		/* 
		 * If the Qivicon bnd builder is to be executed before the Java builder, 
		 * swap their order.
		 * This should actually never occur because the Qivicon bnd builder can
		 * only be added to a Java project (i.e. where a java builder is already
		 * present) and only then gets added at the end
		 */
		if (qiviconBuilderIndex < javaBuilderIndex) {
			final ICommand qiviconBuilder = commands[qiviconBuilderIndex];
			commands[qiviconBuilderIndex] = commands[javaBuilderIndex];
			commands[javaBuilderIndex] = qiviconBuilder;
		}
		description.setBuildSpec(commands);
		project.setDescription(description, null);
	}

	static final void addQiviconBndBuilder(final IProject project) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final ICommand[] commands = description.getBuildSpec();
		requireNonNull(commands, PROJECT_BUILDERS_NULL_ERROR);
		// add builder to project
		final ICommand command = description.newCommand();
		command.setBuilderName(QiviconBuilder.BUILDER_ID);
		final ICommand[] newCommands = new ICommand[commands.length + 1];
		// Add it after all other builders, especially after the Java builder
		System.arraycopy(commands, 0, newCommands, 0, commands.length);
		newCommands[newCommands.length - 1] = command;
		description.setBuildSpec(newCommands);
		project.setDescription(description, null);
	}

	static final void addQiviconBndBuilderNature(final IProject project) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final String[] natures = description.getNatureIds();
		requireNonNull(natures, PROJECT_NATURES_NULL_ERROR);
		final String[] newNatures = new String[natures.length + 1];
		System.arraycopy(natures, 0, newNatures, 0, natures.length);
		newNatures[natures.length] = QiviconBuilderNature.NATURE_ID;
		// Validate the natures
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IStatus status = workspace.validateNatureSet(newNatures);
		// Only apply new nature, if the status is ok
		if (status.getCode() != IStatus.OK) {
			throw new CoreException(status);
		}
		description.setNatureIds(newNatures);
		project.setDescription(description, null);
	}

	static final void removeQiviconBndBuilder(final IProject project) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final ICommand[] commands = description.getBuildSpec();
		requireNonNull(commands, PROJECT_BUILDERS_NULL_ERROR);
		final ICommand[] newCommands = Stream.of(commands).filter(Objects::nonNull).filter(com -> !(QiviconBuilder.BUILDER_ID.equals(com.getBuilderName()))).toArray(ICommand[]::new);
		if (commands.length > newCommands.length) {
			description.setBuildSpec(newCommands);
			project.setDescription(description, null);
		}
	}

	static final void removeQiviconBndBuilderNature(final IProject project) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final String[] natures = description.getNatureIds();
		requireNonNull(natures, PROJECT_NATURES_NULL_ERROR);
		final String[] newNatures = Stream.of(natures).filter(Objects::nonNull).filter(not(QiviconBuilderNature.NATURE_ID::equals)).toArray(String[]::new);
		// Validate the natures
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IStatus status = workspace.validateNatureSet(newNatures);
		// Only apply new nature, if the status is ok
		if (status.getCode() != IStatus.OK) {
			throw new CoreException(status);
		}
		description.setNatureIds(newNatures);
		project.setDescription(description, null);
	}

	/**
	 * Get the manifest from project.
	 * 
	 * @param project project to check for MANIFEST.MF
	 * @return optional containing manifest location or empty optional if not found
	 */
	static final Optional<IPath> getManifestLocation(final IProject project) {
		final IResource manifestFileResource = project.findMember(QiviconBuilderUtils.MANIFEST_LOCATION);
		if (manifestFileResource == null || !manifestFileResource.exists()) {
			// Skip or abort builder, as it will not be a valid JAR file without MANIFEST
			return Optional.empty();
		}
		return Optional.ofNullable(manifestFileResource.getFullPath());
	}

	/**
	 * Extract projects out of selected objects (filter for type {@link IProject} and {@link IJavaProject}).
	 * 
	 * @param selection selection containing arbitrary types
	 * @return subset of selection containing only projects as unmodifiable collection
	 */
	static final Collection<IProject> extractSelectedProjects(final IStructuredSelection selection) {
		final Collection<IProject> selectedProjects = new HashSet<>();
		final Iterator<?> iterator = selection.iterator();
		while (iterator.hasNext()) {
			final Object element = iterator.next();
			// IJavaProject does not extend IProject ffs
			if (element instanceof IJavaProject) {
				final IJavaProject javaProject = (IJavaProject)element;
				selectedProjects.add(javaProject.getProject());
			} else
			// The project class hierarchy in Eclipse is really this messed-up 
			if (element instanceof IProject) {
				final IProject project = (IProject)element;
				selectedProjects.add(project);
			}
			/* Otherwise we don't know how to get the project,
			 * maybe there are some more custom ICustomProjects out there that copied this bad practice
			 */
		}
		return Collections.unmodifiableCollection(selectedProjects);
	}

	/**
	 * Creates a <code>CoreException</code> with the given parameters.
	 *
	 * @param message   a string with the message
	 * @param exception the exception to be wrapped, or <code>null</code> if none
	 * @return a CoreException
	 */
	static final CoreException createCoreException(String message, final Throwable exception) {
		if (message == null) {
			message = ""; //$NON-NLS-1$
		}
		return new CoreException(new Status(IStatus.ERROR, QiviconBuilder.BUILDER_ID, INTERNAL_ERROR, message, exception));
	}

	static final MessageConsoleStream getStreamForLoggingToEclipseConsole(final String consoleName) {
		final MessageConsole console = findOrCreateConsole(consoleName);
		ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] { console });
		ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
		final MessageConsoleStream stream = console.newMessageStream();
		// Redirect system.err and system.out to the Eclipse console as well
		System.setErr(new PrintStream(stream));
		System.setOut(new PrintStream(stream));
		return stream;
	}

	private static final MessageConsole findOrCreateConsole(final String name) {
		final ConsolePlugin plugin = ConsolePlugin.getDefault();
		final IConsoleManager conMan = plugin.getConsoleManager();
		final IConsole[] existing = conMan.getConsoles();
		for (int i = 0; i < existing.length; i++) {
			if (name.equals(existing[i].getName())) {
				return (MessageConsole) existing[i];
			}
		}
		// no console found, so create a new one
		final MessageConsole myConsole = new MessageConsole(name, null);
		conMan.addConsoles(new IConsole[] { myConsole });
		return myConsole;
	}

	static final <T> Predicate<T> not(Predicate<T> t) {
		return t.negate();
	}

}
