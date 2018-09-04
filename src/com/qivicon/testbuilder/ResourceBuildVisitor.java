package com.qivicon.testbuilder;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;

public class ResourceBuildVisitor implements IResourceVisitor {

	@Override
	public boolean visit(final IResource res) throws CoreException {
		// build the specified resource
		// return true to continue visiting children
		return true;
	}
}
