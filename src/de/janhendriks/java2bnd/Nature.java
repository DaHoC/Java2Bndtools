package de.janhendriks.java2bnd;

import java.io.IOException;
import java.util.Arrays;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * Nature for the corresponding Java to bnd builder {@link Builder}.
 * It is responsible for adding and removing the Java to bnd builder entry to/from the project builder chain.
 */
public final class Nature implements IProjectNature {

	static final String NATURE_ID = Utils.CORE_PLUGIN_ID + ".nature";
	static final String NATURE_NAME = "Java2bnd nature";

	private MessageConsoleStream consoleStream;
	private IProject project;

	@Override
	public void configure() throws CoreException {
		// only called once the nature has been set
		log(String.format("%s: configure called, adding %s if not already present", Utils.CORE_PLUGIN_ID, Builder.BUILDER_NAME));
		if (Utils.isJavaToBndProject(project) || !Utils.isJavaProjectAndNotBndProject(project)) {
			log(String.format("%s: configure finished, project already was setup correctly or was not a Java project or had a bnd nature assigned to it", Utils.CORE_PLUGIN_ID));
			return;
		}
		Utils.addJavaToBndBuilder(project, null);
		log(String.format("%s: configure finished, resulting new project builders: %s", Utils.CORE_PLUGIN_ID, Arrays.asList(project.getDescription().getBuildSpec())));
		cleanup();
	}

	@Override
	public void deconfigure() throws CoreException {
		// only called once the nature has been unset
		log(String.format("%s: deconfigure called, removing %s if present", Utils.CORE_PLUGIN_ID, Builder.BUILDER_NAME));
		if (!Utils.isJavaToBndProject(project) || !Utils.isJavaProjectAndNotBndProject(project)) {
			log(String.format("%s: deconfigure finished, project already was setup correctly or was not a Java project or had a bnd nature assigned to it", Utils.CORE_PLUGIN_ID));
			return;
		}
		Utils.removeJavaToBndBuilder(project, null);
		log(String.format("%s: deconfigure finished, resulting project builders: %s", Utils.CORE_PLUGIN_ID, Arrays.asList(project.getDescription().getBuildSpec())));
		cleanup();
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
		if (!Utils.DEBUG_OUTPUT) {
			return;
		}
		if (this.consoleStream == null) {
			this.consoleStream = Utils.getStreamForLoggingToEclipseConsole(NATURE_NAME + " console");
			this.consoleStream.setActivateOnWrite(true);
		}
		this.consoleStream.println(message);
	}

	private void cleanup() {
		if (this.consoleStream != null) {
			try {
				this.consoleStream.close();
			} catch (IOException e) {
				// Hide exception
			}
			this.consoleStream = null;
		}
	}

}
