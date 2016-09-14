package com.projectkaiser.scm.vcs.svn;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.junit.After;
import org.mockito.Mockito;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNWCClient;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.abstracttest.VCSAbstractTest;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSLockedWorkingCopy;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class SVNVCSTest extends VCSAbstractTest {

	private SVNVCS svn;
	private SVNURL localRepoUrl;
	private SVNRepository svnRepo;
	private SVNWCClient mockedSVNRevertClient;
	private RuntimeException testSvnRevertException = new RuntimeException("test exeption on svn revert");
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		localRepoUrl = SVNRepositoryFactory.createLocalRepository(new File(repoUrl.replace("file://", "")), true, true);
		
		svnRepo = SVNRepositoryFactory.create(localRepoUrl);

		svn.createBranch("", MASTER_BRANCH, null);
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
	
	private void listEntries(Set<String> entries, String path) throws SVNException {
		@SuppressWarnings("unchecked")
		Collection<SVNDirEntry> subEntries = svnRepo.getDir(path, -1, null, (Collection<SVNDirEntry>) null);
		Iterator<SVNDirEntry> iterator = subEntries.iterator();
		while (iterator.hasNext()) {
			SVNDirEntry entry = iterator.next();
			entries.add((path.equals("") ? "" : path + "/") + entry.getName());
			if (entry.getKind() == SVNNodeKind.DIR) {
				listEntries(entries, (path.equals("")) ? entry.getName() : path + "/" + entry.getName());
			}
		}
	}
	

	@Override
	protected Set<String> getBranches() throws Exception {
		Set<String> res = new HashSet<>();
		listEntries(res, "");
		return res;
	}
	
	@Override
	protected Set<String> getCommitMessagesRemote(String branchName) throws Exception {
		long startRevision = 0;
		long endRevision = -1; // HEAD (the latest) revision
		Set<String> res = new HashSet<>();

		@SuppressWarnings("unchecked")
		Collection<SVNLogEntry> logEntries = ((SVNVCS) vcs).getRepository().log(new String[] { branchName }, 
				null, startRevision, endRevision, true, true);

		for (Iterator<SVNLogEntry> entries = logEntries.iterator(); entries.hasNext();) {
			SVNLogEntry logEntry = entries.next();
			if (logEntry.getMessage() != null) {
				res.add(logEntry.getMessage());
			}
		}
		return res;
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
	
	@Override
	protected void checkout(String branchName, IVCSLockedWorkingCopy wc) throws Exception {
		svn.checkout(localRepoUrl.toString() + "/" + branchName, wc.getFolder());
	}

	@Override
	protected void sendFile(IVCSLockedWorkingCopy wc, String branchName, String filePath, String commitMessage)
			throws Exception {
		svn
				.getClientManager()
				.getWCClient()
				.doAdd(new File(wc.getFolder(), filePath), 
						true /* force, avoiding "file is already under version control" exception*/,
						false, false, SVNDepth.EMPTY, false, true);

		svn
				.getClientManager()
				.getCommitClient()
				.doCommit(new File[] { wc.getFolder() }, false, commitMessage,
						new SVNProperties(), null, false, false, SVNDepth.INFINITY);
	}
}
