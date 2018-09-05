package com.qivicon.testbuilder;

import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.runtime.CoreException;

public class ResourceBuildDeltaVisitor implements IResourceDeltaVisitor {

	@Override
	public boolean visit(final IResourceDelta res) throws CoreException {
		// build the specified resource
		// return true to continue visiting children
		System.out.println(String.format("QIVICONBUILDER: Incremental builder for %s (doing nothing atm)", res.getFullPath().toOSString()));
		return true;
	}

}
