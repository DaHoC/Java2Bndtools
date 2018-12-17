package de.janhendriks.java2bnd;

import java.util.Collection;
import java.util.Objects;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
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
				.forEach(Utils.REMOVE_JAVA_TO_BNDBUILDER_NATURE);
		return null;
	}

}
