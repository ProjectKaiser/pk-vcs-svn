package org.scm4j.vcs.svn;

import org.scm4j.vcs.api.exceptions.EVCSException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.apache.commons.lang3.StringUtils;

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

	public static void createFolderStructure(SVNVCS svn, String commitMessage) {
		try {
			svn
					.getClientManager()
					.getCommitClient()
					.doMkDir(new SVNURL[] {
							SVNURL.parseURIEncoded(StringUtils.appendIfMissing(svn.getRepoUrl(), "/") + SVNVCS.MASTER_PATH),
							SVNURL.parseURIEncoded(StringUtils.appendIfMissing(svn.getRepoUrl(), "/") + SVNVCS.BRANCHES_PATH),
							SVNURL.parseURIEncoded(StringUtils.appendIfMissing(svn.getRepoUrl(), "/") + SVNVCS.TAGS_PATH)},
							commitMessage);
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}
}
