package de.janhendriks.java2bnd;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

public final class RemoveNatureCommand extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent executionEvent) throws ExecutionException {
		final IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(executionEvent);
		final Collection<IProject> selectedProjects = Utils.extractSelectedJavaProjects(selection);
		selectedProjects.stream().filter(Objects::nonNull)
				.filter(Utils::isJavaProjectAndNotBndProject)
				.filter(Utils::isJavaToBndProject)
				.forEach(REMOVE_JAVA_TO_BNDBUILDER_NATURE);
		return null;
	}

	private static final Consumer<IProject> REMOVE_JAVA_TO_BNDBUILDER_NATURE = project -> {
		try {
			Utils.removeJavaToBndNature(project, null);
		} catch (CoreException e) {
			// Ignore and skip this project and hide error, there's not much we can do here
		}
	};

}
