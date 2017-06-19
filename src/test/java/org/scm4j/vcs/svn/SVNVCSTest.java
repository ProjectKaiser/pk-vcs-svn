package org.scm4j.vcs.svn;

import org.junit.After;
import org.mockito.Mockito;
import org.scm4j.vcs.api.IVCS;
import org.scm4j.vcs.api.abstracttest.VCSAbstractTest;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.SVNWCClient;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

public class SVNVCSTest extends VCSAbstractTest {

	private static final String FOLDER_STRUCT_CREATED_COMMIT_MESSAGE = "trunk/ and branches/ created";
	private SVNVCS svn;
	private SVNRepository svnRepo;
	private SVNWCClient mockedSVNRevertClient;
	private final RuntimeException testSvnRevertException = new RuntimeException("test exeption on svn revert");
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		svnRepo = SVNVCSUtils.createRepository(new File(repoUrl.replace("file://", "")));
		SVNVCSUtils.createFolderStructure(svn, FOLDER_STRUCT_CREATED_COMMIT_MESSAGE);
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
