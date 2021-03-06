[![Release](https://jitpack.io/v/scm4j/scm4j-vcs-svn.svg)](https://jitpack.io/#scm4j/scm4j-vcs-svn)
[![Build Status](https://travis-ci.org/scm4j/scm4j-vcs-svn.svg?branch=develop)](https://travis-ci.org/scm4j/scm4j-vcs-svn)
[![Coverage Status](https://coveralls.io/repos/github/scm4j/scm4j-vcs-svn/badge.svg?branch=develop)](https://coveralls.io/github/scm4j/scm4j-vcs-svn?branch=develop)

# Overview
scm4j-vcs-svn is lightweight library for execute basic SVN VCS operations (merge, branch create etc). It uses [scm4j-vcs-api](https://github.com/scm4j/scm4j-vcs-api) exposing IVCS implementation for SVN repositories and [SVNKit](https://svnkit.com/) as framework to work with SVN repositories.
Features:
- Branch create and remove
- Branch merge returning result(success or list of conflicted files)
- Commit messages list
- Summarized diff between branches
- Branches list
- File content getting and setting
- File create and remove

Use cases
- VCS server hooks
- Build machines
  - checking in\out, tagging
- Software project management systems
  - Create own branches from GUI, browse commits, product versions management, etc
- Product release automation
  - automatic merging, forking, tagging, version bumping, etc
  - Example: [scm4j-releaser](https://github.com/scm4j/scm4j-releaser)


# Terms
- Workspace Home
  - Local home folder of all folders used by vcs-related operations. See [scm4j-vcs-api](https://github.com/scm4j/scm4j-vcs-api) for details
- Locked Working Copy, LWC
  - Local folder where vcs-related operations are executed. Provides thread- and process-safe repository of working folders. See [scm4j-vcs-api](https://github.com/scm4j/scm4j-vcs-api) for details
- Test Repository
  - A local file-based SVN repository used for functional testing
  - Creates new before and deletes after each test automatically
  - Named randomly (uuid is used) 

# Using scm4j-vcs-svn
- Add github-hosted scm4j-vcs-svn project as maven dependency using [jitpack.io](https://jitpack.io/). As an example, add following to gradle.build file:
	```gradle
	allprojects {
		repositories {
			maven { url "https://jitpack.io" }
		}
	}
	
	dependencies {
		// versioning: master-SNAPSHOT (lastest build, unstable), + (lastest release, stable) or certain version (e.g. 1.1)
		compile 'com.github.scm4j:scm4j-vcs-svn:+'
	}
	```
	Or download release jars from https://github.com/scm4j/scm4j-vcs-svn/releases
- Code snippet
	```java
	public static final String WORKSPACE_DIR = System.getProperty("java.io.tmpdir") + "svn-workspaces";
	IVCSWorkspace workspace = new VCSWorkspace(WORKSPACE_DIR);
	String repoUrl = "https://github.com/MyUser/MyRepo";
	IVCSRepositoryWorkspace repoWorkspace = workspace.getVCSRepositoryWorkspace(repoUrl);
	IVCS vcs = new SVNVCS(repoWorkspace, "username", "pass");
	```
- Use methods of `IVCS` interface. See [scm4j-vcs-api](https://github.com/scm4j/scm4j-vcs-api) for details
- Use `vcs.setProxy()` and `vcs.setCredentials()` if necessary

# Implementation details
- [SVNKit](https://svnkit.com/) is used for manage SVN repositories
- LWC is obtained automatically when necessary

# Functional testing
- To execute tests just run SVNVCSTest class as JUnit test. Tests from VCSAbstractTest class will be executed. See  [scm4j-vcs-test](https://github.com/scm4j/scm4j-vcs-test) for details
- Or run `gradle test`

# Limitations
- According to IVCS description `IVCS.getBranches()` should return list of user-created branches. But a branch and a dir are the same for SVN. So `SVNVCS.getBranches()` returns set of first-level folders of "Branches/" branch and "Trunk" branch. I.e.:
	- Assume we have following directory structure:
		- Branches/Br1/Folder/file.txt
		- Branches/Br2/Folder/file.txt
		- Trunk/Folder/file.txt
		- Tags/Tag1/
	- Then `SVNVCS.getBranches()` method will return [Br1, Br2, Trunk]
