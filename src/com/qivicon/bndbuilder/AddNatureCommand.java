package com.qivicon.bndbuilder;

import static com.qivicon.bndbuilder.QiviconBuilderUtils.not;

import java.util.Collection;
import java.util.Objects;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

public final class AddNatureCommand extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent executionEvent) throws ExecutionException {
		final IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(executionEvent);
		final Collection<IProject> selectedProjects = QiviconBuilderUtils.extractSelectedProjects(selection);
		selectedProjects.stream().filter(Objects::nonNull)
				.filter(QiviconBuilderUtils::isJavaProjectAndNotBndProject)
				.filter(not(QiviconBuilderUtils::isQiviconBndBuilderProject))
				.forEach(QiviconBuilderUtils.ADD_QIVICONBNDBUILDER_NATURE);
		return null;
	}

}
