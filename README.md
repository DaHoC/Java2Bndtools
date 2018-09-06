# Qivicon bnd builder

## What is it and what does it do?
Eclipse PDE builder plug-in to provide arbitrary build artifacts as bundles to a bnd workspace repository, comprising of
1. Incremental Builder
1. Project nature

## Prerequisites
1. Eclipse workspace with bndtools workspace with bnd repository named "Local"
1. Same Eclipse workspace with plain Java project with com.qivicon.testbuilder.qiviconbuildernature nature and following .project entries / builder:

	<?xml version="1.0" encoding="UTF-8"?>
	<projectDescription>
		...
		<buildSpec>
			<buildCommand>
				<name>org.eclipse.jdt.core.javabuilder</name>
				<arguments>
				</arguments>
			</buildCommand>
			<buildCommand>
				<name>com.qivicon.testbuilder.qiviconbuilder</name>
				<arguments>
				</arguments>
			</buildCommand>
		</buildSpec>
		<natures>
			<nature>org.eclipse.jdt.core.javanature</nature>
			<nature>com.qivicon.testbuilder.qiviconbuildernature</nature>
		</natures>
	</projectDescription>

The `META-INF/MANIFEST.MF` of the "foreign" Java project has to have the following attributes (`Bundle-Version` defaults to 0.0.0 if not specified) as well as a newline at the end:

	Manifest-Version: 1.0
	Name: Acme Dummy
	Bundle-Name: Dummy project
	Bundle-SymbolicName: com.acme.dummy
	Bundle-ManifestVersion: 2
	Bundle-Version: 2.0.0
	


## How to use?

## Open issues
1. Refactor and cleanup of this mess, e.g. make not that verbose by introducing a "debug" flag
1. Support several (non-default) output folders, also for non-java src folders (e.g. groovy support)
1. Support incremental builds (to some extent)?

