# Overview
Pk-vcs-svn is lightweight library for execute basic SVN VCS operations (merge, branch create etc). It uses [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) exposing IVCS implementation for SVN repositories and [SVNKit](https://svnkit.com/) as framework to work with SVN repositories

# Terms
- Workspace Home
  - Home folder of all folders used by vcs-related operations. See [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) for details
- Repository Workspace
  - Folder for LWC folders related to Repository of one type. See [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) for details
- Locked Working Copy, LWC
  - Folder where vcs-related operations are executed. Provides thread- and process-safe repository of working folders. See [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) for details
- Test Repository
  - A SVN repository used for functional testing
  - Local file system is used, no dedicated server
  - Creates new before and deletes after each test
  - Named randomly (uuid is used) 

# Using pk-vcs-git
- Add github-hosted pk-vcs-svn project as maven dependency using [jitpack.io](https://jitpack.io/). As an example, add following to gradle.build file:
	```gradle
	allprojects {
		repositories {
			maven { url "https://jitpack.io" }
		}
	}
	
	dependencies {
		compile 'com.github.ProjectKaiser:pk-vcs-svn:master-SNAPSHOT'
	}
	```
- Create Workspace Home instance providing path to any folder as Workspace Home folder path. This folder will contain repositories folders (if different vcs or repositories are used)
```java
	public static final String WORKSPACE_DIR = System.getProperty("java.io.tmpdir") + "git-workspaces";
	...
	IVCSWorkspace workspace = new VCSWorkspace(WORKSPACE_DIR);
	...
```
- Obtain Repository Workspace from Workspace Home providing a certain Repository's url. The obtained Repository Workspace will represent a folder within Workspace Home dir which will contain all Working Copies relating to the provided VCS Repository  
```java
	String repoUrl = "https://github.com/ProjectKaiser/pk-vcs-api";
	IVCSRepositoryWorkspace repoWorkspace = workspace.getVCSRepositoryWorkspace(repoUrl);
```
- Create `SVNVCS` instance providing Repository Workspace, username and password for Repository
```java
	IVCS vcs = new SVNVCS(repoWorkspace, username, pass);
```
- Use methods of `IVCS` interface. See [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) for details
- Use `vcs.setProxy()` and `vcs.setCredentials()` if neccessary

# Implementation details
- [SVNKit](https://svnkit.com/) is used for manage SVN repositories
- LWC is obtained if neccessary

# Functional testing
- To execute tests just run SVNVCSTest class as JUnit test. Tests from VCSAbstractTest class will be executed. See  [pk-vcs-test](https://github.com/ProjectKaiser/pk-vcs-test) for details
