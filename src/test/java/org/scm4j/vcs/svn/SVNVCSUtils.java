package org.scm4j.vcs.svn;

import org.scm4j.vcs.api.exceptions.EVCSException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;

import java.io.File;

public class SVNVCSUtils {
	public static SVNRepository createRepository(File repoDir) {
		try {
			SVNURL localRepoUrl = SVNRepositoryFactory.createLocalRepository(repoDir, true, true);
			return SVNRepositoryFactory.create(localRepoUrl);
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}
}
