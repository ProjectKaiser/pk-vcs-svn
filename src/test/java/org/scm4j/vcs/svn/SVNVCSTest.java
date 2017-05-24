package org.scm4j.vcs.svn;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import org.junit.After;
import org.mockito.Mockito;
import org.scm4j.vcs.svn.SVNVCS;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNWCClient;

import org.scm4j.vcs.api.IVCS;
import org.scm4j.vcs.api.abstracttest.VCSAbstractTest;
import org.scm4j.vcs.api.exceptions.EVCSException;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class SVNVCSTest extends VCSAbstractTest {

	private static final String TRUNK_CREATED_COMMIT_MESSAGE = "trunk/ created";
	private static final String BRANCHES_CREATED_COMMIT_MESSAGE = "branches/ created";
	private SVNVCS svn;
	private SVNRepository svnRepo;
	private SVNWCClient mockedSVNRevertClient;
	private final RuntimeException testSvnRevertException = new RuntimeException("test exeption on svn revert");
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		SVNURL localRepoUrl = SVNRepositoryFactory.createLocalRepository(new File(repoUrl.replace("file://", "")), true, true);
		
		svnRepo = SVNRepositoryFactory.create(localRepoUrl);

		createFolder("/" + SVNVCS.MASTER_PATH, TRUNK_CREATED_COMMIT_MESSAGE);
		createFolder("/" + SVNVCS.BRANCHES_PATH, BRANCHES_CREATED_COMMIT_MESSAGE);
	}
	
	private void createFolder(String folderName, String commitMessage) {
		try {
			svn
					.getClientManager()
					.getCommitClient()
					.doMkDir(new SVNURL[] {SVNURL.parseURIEncoded(svn.getRepoUrl() + folderName)}, 
							commitMessage);
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}

	@After
	public void tearDown() throws IOException {
		svnRepo.closeSession();
	}

	@Override
	public String getVCSTypeString() {
		return "svn";
	}

	@Override
	protected String getTestRepoUrl() {
		return "file:///" + localVCSWorkspace.getHomeFolder().getPath().replace("\\", "/") + "/";
	}

	@Override
	protected IVCS getVCS(IVCSRepositoryWorkspace mockedVCSRepo) {
		// nulls as user and pwd because we using file repository, not server
		svn = Mockito.spy(new SVNVCS(mockedVCSRepo, null, null));
		return svn;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected void setMakeFailureOnVCSReset(Boolean doMakeFailure) {
		if (doMakeFailure) {
			mockedSVNRevertClient = Mockito.spy(svn.getRevertClient((DefaultSVNOptions) svn.getOptions()));
			Mockito.doReturn(mockedSVNRevertClient).when(svn).getRevertClient(Mockito.any(DefaultSVNOptions.class));
			try {
				Mockito.doThrow(testSvnRevertException).when(mockedSVNRevertClient)
						.doRevert(Mockito.any(File[].class), Mockito.any(SVNDepth.class), 
								Mockito.isNull(Collection.class));
			} catch (SVNException e) {
				throw new RuntimeException(e);
			}
		} else {
			if (mockedSVNRevertClient != null) {
				Mockito.doCallRealMethod().when(((SVNVCS) vcs).getRevertClient((DefaultSVNOptions) ((SVNVCS) vcs).getOptions()));
				mockedSVNRevertClient = null;
			}
		}
	}
}
