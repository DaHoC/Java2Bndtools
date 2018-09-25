# Qivicon bnd builder

## What is it and what does it do?
Eclipse PDE builder plug-in to provide arbitrary build artifacts as bundles to a bnd workspace repository, comprising of
1. Incremental Builder
1. Project nature

![Example Eclipse flow](https://qivicon-wbench.psst.t-online.corp/gitlab/jan.hendriks/QiviconBndBuilder/raw/master/ExampleFlow.jpg "Example Eclipse flow")

## Prerequisites
1. Eclipse workspace with bndtools workspace with bnd repository named "Local"
1. Same Eclipse workspace containing a Java project
1. The `META-INF/MANIFEST.MF` of the Java project must be present, readable and must have the following attributes (`Bundle-Version` defaults to `0.0.0` if not specified), mind the mandatory newline at the end:

	Manifest-Version: 1.0
	Bundle-Name: Dummy project
	Bundle-SymbolicName: com.foo.bar
	Bundle-ManifestVersion: 2
	Bundle-Version: 2.0.0

## How to install?
TODO

## How to use?
You need an Eclipse workspace containing

* a bnd workspace with the mandatory bnd cnf project folder
* a bnd workspace repository called "Local"
* a Java project (containing the org.eclipse.jdt.core.javanature nature and a META-INF/MANIFEST.MF file) in the same Eclipse workspace

To associate the plug-in with a Java project, you need to add a nature (with a builder) to the project you want to provide in the local bnd workspace.
To do so, there are two ways:

### GUI way (recommended)
Select the Java project, go into its properties (e.g. by right-clicking the project → *Properties*), select *Project Natures* → *Add...* → *QIVICON bnd builder nature* as depicted below:

![Add project nature](https://qivicon-wbench.psst.t-online.corp/gitlab/jan.hendriks/QiviconBndBuilder/raw/master/AddProjectNature.jpg "Add project nature")

After hitting *Apply and Close*, the project nature is added, the corresponding builder is added and registered and the project is build and should immediately appear in the bnd workspace "Local" repository.

### Manual way
For the Java project add the `com.qivicon.bndbuilder.qiviconbndbuildernature` and `buildCommand` `com.qivicon.bndbuilder.qiviconbndbuilder` as last build entry as shown in the following `.project` entries / builder:

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
				<name>com.qivicon.bndbuilder.qiviconbndbuilder</name>
				<arguments>
				</arguments>
			</buildCommand>
		</buildSpec>
		<natures>
			<nature>org.eclipse.jdt.core.javanature</nature>
			<nature>com.qivicon.bndbuilder.qiviconbndbuildernature</nature>
		</natures>
	</projectDescription>

Each time when a *full build* on the Java project is triggered, e.g. by doing a *Project → Clean…*, the updated project should appear as bundle in the bnd workspace "Local" repository.

The name of the bundle corresponds to the `Bundle-SymbolicName` `MANIFEST.MF` entry, the version to `Bundle-Version`.

This bundle can now be referenced by a bnd project.

### Example usage
Consider a Java project in your workspace that has a `Bundle-SymbolicName` of `com.foo` and a `Bundle-Version` of `2.1.0`.

Besides this project, the `de.bar.ui` bnd project is present in our Eclipse workspace, which should require the `com.foo` bundle as dependency.

Do a full build of the Java project `com.foo`, the corresponding bundle should now be listed in the "Local" bnd workspace repository.

Now reference if from e.g. the `bnd.bnd` file, e.g.

	-buildpath = com.foo;version=2.1.0

Now the `de.bar.ui` bnd project references the `com.foo` bundle as dependency and the exposed API (including JavaDoc) can be used.

## How does it work?
If the Java project is given the additional nature (and builder) as mentioned before, the builder registers itself as the last builder to execute in the chain, to make sure it's called after all other builders doing e.g. source generation or resource handling, especially after the Java builder.

During each *full* build of this Java project, the Qivicon bnd builder packs generated Java artifacts of the project into a temporary jar file using the Eclipse-internal jar package exporter with custom settings.

This temporary JAR file is passed via stream to the bnd workspace "Local" repository where it should appear.
It is automatically overwritten for each new full build and the bnd workspace repository is refreshed automatically.

## Open issues & to-do
1. Support incremental builds to some extent
1. Integrate correct progress meter (currently unused)
1. Develop strategy to mark projects that the plugin should consider, i.e. set the nature and builder by selection
1. Provide a good way to ship this plugin to team developers
1. Check if there is nothing preventing this project to become open-source
