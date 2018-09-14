package com.qivicon.bndbuilder;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * Nature for the corresponding Qivicon bnd builder.
 */
public class QiviconBuilderNature implements IProjectNature {

	static final String NATURE_ID = "com.qivicon.bndbuilder.qiviconbndbuildernature";

	private MessageConsoleStream consoleStream;
	private IProject project;

	@Override
	public void configure() throws CoreException {
		log("QIVICONBUILDER: configure called");
		/*
		 * Adds nature-specific information to the project. Here: adding a builder to a
		 * project's build specification)
		 */
		final IProjectDescription desc = project.getDescription();
		final ICommand[] commands = desc.getBuildSpec();
		boolean found = false;

		for (int i = 0; i < commands.length; ++i) {
			if (commands[i].getBuilderName().equals(QiviconBuilder.BUILDER_ID)) {
				found = true;
				break;
			}
		}

		if (!found) {
			// add builder to project
			final ICommand command = desc.newCommand();
			command.setBuilderName(QiviconBuilder.BUILDER_ID);
			final ICommand[] newCommands = new ICommand[commands.length + 1];

			// Add it after all other builders
			System.arraycopy(commands, 0, newCommands, 0, commands.length);
			newCommands[commands.length - 1] = command;
			desc.setBuildSpec(newCommands);
			project.setDescription(desc, null);
		}
		log(String.format("QIVICONBUILDER: configure finished for %s", Arrays.asList(desc.getBuildSpec())));
	}

	@Override
	public void deconfigure() throws CoreException {
		log("QIVICONBUILDER: deconfigure called");
		// Remove the nature-specific information here
		final IProjectDescription desc = project.getDescription();
		final ICommand[] commands = desc.getBuildSpec();
		final List<ICommand> commandList = Arrays.asList(commands);
		final Iterator<ICommand> commandListIterator = commandList.iterator();
		boolean found = false;
		while (commandListIterator.hasNext()) {
			final ICommand currentCommand = commandListIterator.next();
			if (currentCommand.getBuilderName().equals(QiviconBuilder.BUILDER_ID)) {
				commandListIterator.remove();
				found = true;
				break;
			}
		}
		if (found) {
			// remove builder from project
			final ICommand[] newCommands = commandList.toArray(commands);
			desc.setBuildSpec(newCommands);
			project.setDescription(desc, null);
		}
		log(String.format("QIVICONBUILDER: deconfigure finished for %s", Arrays.asList(desc.getBuildSpec())));
	}

	@Override
	public IProject getProject() {
		return project;
	}

	@Override
	public void setProject(final IProject project) {
		this.project = project;
	}

	private void log(final String message) {
		if (!QiviconBuilder.DEBUG_OUTPUT) {
			return;
		}
		if (this.consoleStream == null) {
			this.consoleStream = QiviconBuilder.getStreamForLoggingToEclipseConsole();
			this.consoleStream.setActivateOnWrite(true);
		}
		this.consoleStream.println(message);
	}


}
