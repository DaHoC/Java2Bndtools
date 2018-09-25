package com.qivicon.bndbuilder;

import java.util.Arrays;
import java.util.stream.Stream;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * Nature for the corresponding Qivicon bnd builder {@link QiviconBuilder}.
 */
public class QiviconBuilderNature implements IProjectNature {

	private static final String NATURE_NAME = "QIVICON bnd builder nature";

	private MessageConsoleStream consoleStream;
	private IProject project;

	@Override
	public void configure() throws CoreException {
		log(String.format("QIVICONBUILDER: configure called, adding %s if not already present", QiviconBuilder.BUILDER_NAME));
		final IProjectDescription desc = project.getDescription();
		final ICommand[] commands = desc.getBuildSpec();
		final boolean found = Stream.of(commands).anyMatch(com -> QiviconBuilder.BUILDER_ID.equals(com.getBuilderName()));
		if (!found) {
			// add builder to project
			final ICommand command = desc.newCommand();
			command.setBuilderName(QiviconBuilder.BUILDER_ID);
			final ICommand[] newCommands = new ICommand[commands.length + 1];
			// Add it after all other builders, especially after the Java builder
			System.arraycopy(commands, 0, newCommands, 0, commands.length);
			newCommands[newCommands.length - 1] = command;
			desc.setBuildSpec(newCommands);
			project.setDescription(desc, null);
		}
		log(String.format("QIVICONBUILDER: configure finished, resulting project builders: %s", Arrays.asList(desc.getBuildSpec())));
	}

	@Override
	public void deconfigure() throws CoreException {
		log(String.format("QIVICONBUILDER: deconfigure called, removing %s if present", QiviconBuilder.BUILDER_NAME));
		// Remove the nature-specific information here
		final IProjectDescription desc = project.getDescription();
		final ICommand[] commands = desc.getBuildSpec();
		final ICommand[] newCommands = Stream.of(commands).filter(com -> !(QiviconBuilder.BUILDER_ID.equals(com.getBuilderName()))).toArray(ICommand[]::new);
		if (commands.length > newCommands.length) {
			desc.setBuildSpec(newCommands);
			project.setDescription(desc, null);
		}
		log(String.format("QIVICONBUILDER: deconfigure finished, resulting project builders: %s", Arrays.asList(desc.getBuildSpec())));
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
			this.consoleStream = QiviconBuilder.getStreamForLoggingToEclipseConsole(NATURE_NAME + " console");
			this.consoleStream.setActivateOnWrite(true);
		}
		this.consoleStream.println(message);
	}

}
