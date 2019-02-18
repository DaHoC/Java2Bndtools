package de.janhendriks.java2bnd;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.bndtools.api.BndtoolsConstants;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * Utilities class for the Java to bnd builder and nature containing static convenience methods to avoid code duplication.
 */
abstract class Utils {

	static final String CORE_PLUGIN_ID = "de.janhendriks.java2bnd";

	static final String BUILDER_ID = Utils.CORE_PLUGIN_ID + ".builder";
	static final String BUILDER_NAME = "Java2bnd builder";

	static final String NATURE_ID = Utils.CORE_PLUGIN_ID + ".nature";
	static final String NATURE_NAME = "Java2bnd nature";

	static final String BUILDER_PROPERTIES_LOCATION = "META-INF/plugin.properties";
	static final String MANIFEST_LOCATION = "META-INF/MANIFEST.MF";
	static final boolean DEBUG_OUTPUT = false;

	private static final int INTERNAL_ERROR = -10001;

	// Messages thrown as NPE when this is a null value (instead of e.g. an empty array which is valid)
	private static final String PROJECT_NULL_ERROR = "Project must not be null!";
	private static final String PROJECT_DESCRIPTION_NULL_ERROR = "Project description must not be null!";
	private static final String PROJECT_BUILDERS_NULL_ERROR = "Project builder commands must not be null!";
	private static final String PROJECT_NATURES_NULL_ERROR = "Project natures must not be null!";

	// Get from external bndtools API
	private static final String BND_NATURE_ID = BndtoolsConstants.NATURE_ID;
	private static final String BND_BUILDER_ID = BndtoolsConstants.BUILDER_ID;

	private Utils() {
		throw new IllegalAccessError("Cannot instantiate " + this.getClass().getName());
	}

	/**
	 * Check if project has the Java nature but does not at the same time have the Bnd project nature assigned.
	 * Can be used to selectively add the Java2Bnd project builder and nature to matching projects.
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
	 * Check if the given project has the Java2Bnd project nature and corresponding builder assigned.
	 * 
	 * @param project project to check
	 * @return {@code true} if the project has the Java2Bnd project nature and corresponding builder assigned to it, {@code false} otherwise
	 */
	static final boolean isJavaToBndProject(final IProject project) {
		return isMatchingProject(project, NATURE_ID, BUILDER_ID); 
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
	static final void checkBuilderOrdering(final IProject project, final IProgressMonitor monitor) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final ICommand[] commands = description.getBuildSpec();
		requireNonNull(commands, PROJECT_BUILDERS_NULL_ERROR);
		int javaToBndBuilderIndex = -1;
		int javaBuilderIndex = -1;
		// Determine builder positions from project's build specification
		for (int i = 0; i < commands.length; i++) {
			if (JavaCore.BUILDER_ID.equals(commands[i].getBuilderName())) {
				javaBuilderIndex = i;
			} else if (BUILDER_ID.equals(commands[i].getBuilderName())) {
				javaToBndBuilderIndex = i;
			}
			// Exit loop if both indices have already been found
			if (javaBuilderIndex != -1 && javaToBndBuilderIndex != -1) {
				break;
			}
		}
		/* 
		 * If the Java to bnd builder is to be executed before the Java builder, swap their order.
		 * This should actually never occur because the Java2Bnd builder can
		 * only be added to a Java project (i.e. where a java builder is already
		 * present) and only then gets added at the end
		 */
		if (javaToBndBuilderIndex < javaBuilderIndex) {
			final ICommand javaToBndBuilder = commands[javaToBndBuilderIndex];
			commands[javaToBndBuilderIndex] = commands[javaBuilderIndex];
			commands[javaBuilderIndex] = javaToBndBuilder;
		}
		description.setBuildSpec(commands);
		project.setDescription(description, monitor);
	}

	static final void addJavaToBndBuilder(final IProject project, final IProgressMonitor monitor) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final ICommand[] commands = description.getBuildSpec();
		requireNonNull(commands, PROJECT_BUILDERS_NULL_ERROR);
		// add builder to project
		final ICommand command = description.newCommand();
		command.setBuilderName(BUILDER_ID);
		final ICommand[] newCommands = new ICommand[commands.length + 1];
		// Add it after all other builders, especially after the Java builder
		System.arraycopy(commands, 0, newCommands, 0, commands.length);
		newCommands[newCommands.length - 1] = command;
		description.setBuildSpec(newCommands);
		project.setDescription(description, monitor);
	}

	static final void addJavaToBndNature(final IProject project, final IProgressMonitor monitor) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final String[] natures = description.getNatureIds();
		requireNonNull(natures, PROJECT_NATURES_NULL_ERROR);
		final String[] newNatures = new String[natures.length + 1];
		System.arraycopy(natures, 0, newNatures, 0, natures.length);
		newNatures[natures.length] = NATURE_ID;
		// Validate the natures
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IStatus status = workspace.validateNatureSet(newNatures);
		// Only apply new nature, if the status is ok
		if (status.getCode() != IStatus.OK) {
			throw new CoreException(status);
		}
		description.setNatureIds(newNatures);
		project.setDescription(description, monitor);
	}

	static final void removeJavaToBndBuilder(final IProject project, final IProgressMonitor monitor) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final ICommand[] commands = description.getBuildSpec();
		requireNonNull(commands, PROJECT_BUILDERS_NULL_ERROR);
		final ICommand[] newCommands = Stream.of(commands).filter(Objects::nonNull).filter(com -> !(BUILDER_ID.equals(com.getBuilderName()))).toArray(ICommand[]::new);
		if (commands.length > newCommands.length) {
			description.setBuildSpec(newCommands);
			project.setDescription(description, monitor);
		}
	}

	static final void removeJavaToBndNature(final IProject project, final IProgressMonitor monitor) throws CoreException {
		requireNonNull(project, PROJECT_NULL_ERROR);
		final IProjectDescription description = project.getDescription();
		requireNonNull(description, PROJECT_DESCRIPTION_NULL_ERROR);
		final String[] natures = description.getNatureIds();
		requireNonNull(natures, PROJECT_NATURES_NULL_ERROR);
		final String[] newNatures = Stream.of(natures).filter(Objects::nonNull).filter(not(NATURE_ID::equals)).toArray(String[]::new);
		// Validate the natures
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IStatus status = workspace.validateNatureSet(newNatures);
		// Only apply new nature, if the status is ok
		if (status.getCode() != IStatus.OK) {
			throw new CoreException(status);
		}
		description.setNatureIds(newNatures);
		project.setDescription(description, monitor);
	}

	/**
	 * Get the manifest from project.
	 * 
	 * @param project project to check for MANIFEST.MF
	 * @return optional containing manifest location or empty optional if not found
	 */
	static final Optional<IPath> getManifestLocation(final IProject project) {
		final IResource manifestFileResource = project.findMember(Utils.MANIFEST_LOCATION);
		if (manifestFileResource == null || !manifestFileResource.exists()) {
			// Skip or abort builder, as it will not be a valid JAR file without MANIFEST
			return Optional.empty();
		}
		return Optional.ofNullable(manifestFileResource.getFullPath());
	}

	/**
	 * Extract Java projects out of selected objects (filter for type {@link IJavaProject}).
	 * 
	 * @param selection containing arbitrary types
	 * @return subset of selection containing only Java projects as unmodifiable collection
	 */
	static final Collection<IProject> extractSelectedJavaProjects(final IStructuredSelection selection) {
		final Collection<IProject> selectedProjects = new HashSet<>();
		final Iterator<?> iterator = selection.iterator();
		while (iterator.hasNext()) {
			final Object element = iterator.next();
			// IJavaProject does not extend IProject ffs
			if (element instanceof IJavaProject) {
				final IJavaProject javaProject = (IJavaProject)element;
				selectedProjects.add(javaProject.getProject());
			}
			/* Otherwise we don't know how to get the project,
			 * maybe there are some more IProjects / custom ICustomProjects out there
			 * that copied the broken missing IProject inheritance practice
			 */
		}
		return Collections.unmodifiableCollection(selectedProjects);
	}

	static final boolean hasJavaBuildErrors(final IProject project) throws CoreException {
		final IMarker[] javaMarkers = project.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);
		return Stream.of(javaMarkers).anyMatch(marker -> (marker.getAttribute(IMarker.SEVERITY, 0) == IMarker.SEVERITY_ERROR));
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
		return new CoreException(new Status(IStatus.ERROR, BUILDER_ID, INTERNAL_ERROR, message, exception));
	}

	static final MessageConsoleStream getStreamForLoggingToEclipseConsole(final String consoleName) {
		final MessageConsole console = findOrCreateConsole(consoleName);
		ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] { console });
		ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
		return console.newMessageStream();
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
