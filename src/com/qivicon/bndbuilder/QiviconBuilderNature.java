package com.qivicon.bndbuilder;

import java.io.IOException;
import java.util.Arrays;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * Nature for the corresponding Qivicon bnd builder {@link QiviconBuilder}.
 * It is responsible for adding and removing the Qivicon bnd builder entry to/from the project builder chain.
 */
public final class QiviconBuilderNature implements IProjectNature {

	static final String NATURE_ID = QiviconBuilderUtils.CORE_PLUGIN_ID + ".qiviconbndbuildernature";
	static final String NATURE_NAME = "QIVICON bnd builder nature";

	private MessageConsoleStream consoleStream;
	private IProject project;

	@Override
	public void configure() throws CoreException {
		// only called once the nature has been set
		log(String.format("QIVICONBUILDER: configure called, adding %s if not already present", QiviconBuilder.BUILDER_NAME));
		if (QiviconBuilderUtils.isQiviconBndBuilderProject(project) || !QiviconBuilderUtils.isJavaProjectAndNotBndProject(project)) {
			log("QIVICONBUILDER: configure finished, project already was setup correctly or was not a Java project or had a bnd nature assigned to it");
			return;
		}
		QiviconBuilderUtils.addQiviconBndBuilder(project);
		log(String.format("QIVICONBUILDER: configure finished, resulting new project builders: %s", Arrays.asList(project.getDescription().getBuildSpec())));
		cleanup();
	}

	@Override
	public void deconfigure() throws CoreException {
		// only called once the nature has been unset
		log(String.format("QIVICONBUILDER: deconfigure called, removing %s if present", QiviconBuilder.BUILDER_NAME));
		if (!QiviconBuilderUtils.isQiviconBndBuilderProject(project) || !QiviconBuilderUtils.isJavaProjectAndNotBndProject(project)) {
			log("QIVICONBUILDER: deconfigure finished, project already was setup correctly or was not a Java project or had a bnd nature assigned to it");
			return;
		}
		QiviconBuilderUtils.removeQiviconBndBuilder(project);
		log(String.format("QIVICONBUILDER: deconfigure finished, resulting project builders: %s", Arrays.asList(project.getDescription().getBuildSpec())));
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
		if (!QiviconBuilderUtils.DEBUG_OUTPUT) {
			return;
		}
		if (this.consoleStream == null) {
			this.consoleStream = QiviconBuilderUtils.getStreamForLoggingToEclipseConsole(NATURE_NAME + " console");
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
