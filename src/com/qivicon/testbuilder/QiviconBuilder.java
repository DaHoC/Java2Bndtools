package com.qivicon.testbuilder;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Builder for zipping compiled build artifacts of an arbitrary project into an
 * OSGi-compliant JAR bundle file and copy it to a well-defined location, such
 * as a bnd repository location of a bnd workspace.
 * 
 * @see <a href=
 *      "http://help.eclipse.org/photon/index.jsp?topic=%2Forg.eclipse.platform.doc.isv%2Fguide%2FresAdv_builders.htm&cp=2_0_11_1">official
 *      Eclipse Photon documentation about incremental builders</a>
 * 
 *      <a href=
 *      "http://www.eclipse.org/articles/Article-Builders/builders.html">article
 *      about Eclipse project builders and natures</a>
 */
public class QiviconBuilder extends IncrementalProjectBuilder {

	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
		// kind is one of FULL_BUILD, INCREMENTAL_BUILD, AUTO_BUILD, CLEAN_BUILD
		if (kind == IncrementalProjectBuilder.FULL_BUILD) {
			fullBuild(monitor);
		} else {
			final IResourceDelta delta = getDelta(getProject());
			if (delta == null) {
				fullBuild(monitor);
			} else {
				incrementalBuild(delta, monitor);
			}
		}
		// TODO Return something useful
		return null;
	}

	protected void incrementalBuild(final IResourceDelta delta, final IProgressMonitor monitor) throws CoreException {
		// TODO Implement me
		// the visitor does the work.
		delta.accept(new ResourceBuildDeltaVisitor());
	}

	protected void fullBuild(final IProgressMonitor monitor) throws CoreException {
		try {
			// TODO Implement me
			getProject().accept(new ResourceBuildVisitor());
		} catch (CoreException e) {
			// TODO Comment why this does not bother us
		}
	}

	protected void startupOnInitialize() {
		// add builder init logic here
	}

	protected void clean(IProgressMonitor monitor) {
		// add builder clean logic here
	}

}
