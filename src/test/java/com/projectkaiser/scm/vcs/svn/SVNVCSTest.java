package com.projectkaiser.scm.vcs.svn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.PKVCSMergeResult;
import com.projectkaiser.scm.vcs.api.VCSWorkspace;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSBranchExists;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSFileNotFound;

public class SVNVCSTest {
	private static final String MERGE_COMMIT_MESSAGE = "Merged using SVNKit";
	private static final String SVN_USER = "host6";
	private static final String SVN_PWD = "dc638Fg15";

	private static final String BASE_DIR = System.getProperty("java.io.tmpdir") + "pk-vcs-workspaces";
	private static final String WORKSPACE_DIR = BASE_DIR + "/SVNWorkspaces";

	private static final String NEW_BRANCH = "new-branch";
	private static final String SRC_BRANCH = "src-branch";
	private static final String CREATED_SRC_BRANCH_COMMIT_MESSAGE = "created src branch";
	private static final String CREATED_DST_BRANCH_COMMIT_MESSAGE = "created dst branch";
	private static final String DELETE_BRANCH_COMMIT_MESSAGE = "deleted";
	private static final String LINE_1 = "line 1";
	private static final String LINE_2 = "line 2";
	private static final String LINE_3 = "line 3";
	private static final String FILE_1 = "file1.txt";
	private static final String FILE_2 = "file2.txt";
	private static final String FILE1_ADDED_COMMIT_MESSAGE = "file1 added";
	private static final String FILE2_ADDED_COMMIT_MESSAGE = "file2 added";
	private static final String FILE1_CHANGED_COMMIT_MESSAGE = "file1 changed";

	private IVCS svn;
	private SVNVCS svnVCS;
	private String svnTrunkPath;
	private String svnSrcBranchPath;
	private String svnDstBranchPath;
	private SVNURL localRepoUrl;
	private SVNRepository svnRepo;
	private VCSWorkspace svnRepoWorkspace;

	@Before
	public void setUp() throws SVNException, IOException {
		svnRepoWorkspace = VCSWorkspace.getLockedWorkspace(new File(BASE_DIR, "/SVNRepos/").getPath());
		localRepoUrl = SVNRepositoryFactory.createLocalRepository(svnRepoWorkspace.getFolder(), true, true);
		svnRepo = SVNRepositoryFactory.create(localRepoUrl);

		svnVCS = new SVNVCS(null, WORKSPACE_DIR, localRepoUrl.toString(), SVN_USER, SVN_PWD);
		svn = svnVCS;

		// svn.setProxy(PROXY_HOST, PROXY_PORT, PROXY_USER, PROXY_PASS);
		svnTrunkPath = "/trunk";
		svnSrcBranchPath = "/branches/" + SRC_BRANCH;
		svnDstBranchPath = "/branches/" + NEW_BRANCH;
		FileUtils.deleteDirectory(new File(WORKSPACE_DIR));
	}

	@After
	public void tearDown() throws IOException {
		svnRepoWorkspace.setCorrupt(true);
		svnRepoWorkspace.unlock();
		svnRepo.closeSession();
	}

	@SuppressWarnings("rawtypes")
	private void listEntries(List<String> entriesList, String path) throws SVNException {
		Collection entries = svnRepo.getDir(path, -1, null, (Collection) null);
		Iterator iterator = entries.iterator();
		while (iterator.hasNext()) {
			SVNDirEntry entry = (SVNDirEntry) iterator.next();
			entriesList.add("/" + (path.equals("") ? "" : path + "/") + entry.getName());
			if (entry.getKind() == SVNNodeKind.DIR) {
				listEntries(entriesList, (path.equals("")) ? entry.getName() : path + "/" + entry.getName());
			}
		}
	}

	@Test
	public void testCreateAndDeleteBranch() throws SVNException, IOException {
		svn.createBranch("", svnSrcBranchPath, CREATED_SRC_BRANCH_COMMIT_MESSAGE);

		List<String> list = new ArrayList<>();
		listEntries(list, "");
		assertTrue(list.contains(svnSrcBranchPath));
		assertTrue(list.size() == 2); // /branches and /branches/src-branch

		assertContainsCommitsOnly("", CREATED_SRC_BRANCH_COMMIT_MESSAGE);

		try {
			svn.createBranch("", svnSrcBranchPath, CREATED_SRC_BRANCH_COMMIT_MESSAGE);
			fail("EBanchExists is not thrown");
		} catch (EVCSBranchExists e) {
		}

		svn.deleteBranch(svnSrcBranchPath, DELETE_BRANCH_COMMIT_MESSAGE);
		list.clear();
		listEntries(list, "");
		assertFalse(list.contains(svnSrcBranchPath));
		assertTrue(list.size() == 1);
		assertContainsCommitsOnly("", CREATED_SRC_BRANCH_COMMIT_MESSAGE, DELETE_BRANCH_COMMIT_MESSAGE);
	}

	private void assertContainsCommitsOnly(String branch, String... commitMessages) throws SVNException {
		long startRevision = 0;
		long endRevision = -1; // HEAD (the latest) revision

		ArrayList<String> messages = new ArrayList<>(Arrays.asList(commitMessages));

		@SuppressWarnings("unchecked")
		Collection<SVNLogEntry> logEntries = svnVCS.getRepository().log(new String[] { branch }, null, startRevision,
				endRevision, true, true);

		for (Iterator<SVNLogEntry> entries = logEntries.iterator(); entries.hasNext();) {
			SVNLogEntry logEntry = entries.next();
			if (logEntry.getMessage() != null && !messages.remove(logEntry.getMessage())) {
				fail("Unexpected commit met: " + logEntry.getMessage());
			}
		}
		if (messages.size() > 0) {
			fail("Log does not contain following expected commits: " + messages.toString());
		}
	}

	@Test
	public void testMerge() throws SVNException, IOException {

		svn.createBranch("", svnTrunkPath, CREATED_SRC_BRANCH_COMMIT_MESSAGE);
		svn.createBranch(svnTrunkPath, svnSrcBranchPath, CREATED_SRC_BRANCH_COMMIT_MESSAGE);
		svn.createBranch(svnTrunkPath, svnDstBranchPath, CREATED_DST_BRANCH_COMMIT_MESSAGE);

		VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(svnVCS.getRepoFolder());
		try {
			svnVCS.checkout("file:///" + svnRepoWorkspace.getFolder().getPath() + svnSrcBranchPath,
					workspace.getFolder());
			
			
			File file1 = new File(workspace.getFolder(), FILE_1);
			
			setContentAndCommit(workspace, file1, LINE_1, FILE1_ADDED_COMMIT_MESSAGE);
			
			svnVCS.checkout("file:///" + svnRepoWorkspace.getFolder().getPath() + svnDstBranchPath,
					workspace.getFolder());
			
			File file2 = new File(workspace.getFolder(), FILE_2);
			
			setContentAndCommit(workspace, file2, LINE_2, FILE2_ADDED_COMMIT_MESSAGE);
			
			PKVCSMergeResult res = svn.merge(svnSrcBranchPath, svnDstBranchPath, MERGE_COMMIT_MESSAGE);
			svnVCS.checkout("file:///" + svnRepoWorkspace.getFolder().getPath() + svnDstBranchPath,
					workspace.getFolder());
			assertTrue(file1.exists());
			assertTrue(file2.exists());
			assertTrue(res.getSuccess());
			assertTrue(res.getConflictingFiles().isEmpty());
			assertContainsCommitsOnly(svnDstBranchPath, MERGE_COMMIT_MESSAGE, CREATED_DST_BRANCH_COMMIT_MESSAGE, 
					FILE2_ADDED_COMMIT_MESSAGE);
		} finally {
			workspace.unlock();
		}
	}
	
	@Test
	public void testMergeConflict() throws SVNException, IOException {
		svn.createBranch("", svnTrunkPath, CREATED_SRC_BRANCH_COMMIT_MESSAGE);
		
		VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(svnVCS.getRepoFolder());
		try {
			svnVCS.checkout("file:///" + svnRepoWorkspace.getFolder().getPath() + svnTrunkPath,
					workspace.getFolder());
			
			File file = new File(workspace.getFolder(), FILE_1);
			
			setContentAndCommit(workspace, file, LINE_1, FILE1_ADDED_COMMIT_MESSAGE);
			
			svn.createBranch(svnTrunkPath, svnSrcBranchPath, CREATED_SRC_BRANCH_COMMIT_MESSAGE);
			
			setContentAndCommit(workspace, file, LINE_2, FILE1_CHANGED_COMMIT_MESSAGE);
			
			svnVCS.checkout("file:///" + svnRepoWorkspace.getFolder().getPath() + svnSrcBranchPath,
					workspace.getFolder());
			
			setContentAndCommit(workspace, file, LINE_3, FILE1_CHANGED_COMMIT_MESSAGE);
			
			PKVCSMergeResult res = svn.merge(svnSrcBranchPath, svnTrunkPath, MERGE_COMMIT_MESSAGE);
			assertFalse(res.getSuccess());
			assertTrue(res.getConflictingFiles().size() == 1);
			assertTrue(res.getConflictingFiles().contains(file.getName() + ".working"));
		} finally {
			workspace.unlock();
		}
	}

	private void setContentAndCommit(VCSWorkspace workspace, File file, String content, String commitMessage) 
			throws IOException, SVNException {
		Boolean newFile = !file.exists();
		if (!file.exists()) {
			file.createNewFile();
		}
		
		FileWriter writer = new FileWriter(file);
		writer.write(content);
		writer.close();
		
		if (newFile) {
			svnVCS
					.getClientManager()
					.getWCClient()
					.doAdd(file, false, false, false, SVNDepth.EMPTY, false, true);
		}
		
		svnVCS
				.getClientManager()
				.getCommitClient()
				.doCommit(new File[] { workspace.getFolder() }, false, commitMessage,
						new SVNProperties(), null, false, false, SVNDepth.INFINITY);
	}
	
	@Test
	public void testGetSetFileContent() throws IOException, SVNException {
		svn.createBranch("", svnTrunkPath, CREATED_SRC_BRANCH_COMMIT_MESSAGE);
		
		VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(svnVCS.getRepoFolder());
		try {
			svnVCS.checkout("file:///" + svnRepoWorkspace.getFolder().getPath() + svnTrunkPath,
					workspace.getFolder());
			
			File file = new File(workspace.getFolder(), FILE_1);
			
			setContentAndCommit(workspace, file, LINE_1, FILE1_ADDED_COMMIT_MESSAGE);
			
			try {
				assertEquals(svn.getFileContent("ghjjk", file.getName()), LINE_1);
				fail("EVCSFileNotFound exception is not thrown");
			} catch (EVCSFileNotFound e) {
				
			}
			
			assertEquals(svn.getFileContent(svnTrunkPath, file.getName()), LINE_1);
			assertEquals(svn.getFileContent(svnTrunkPath, file.getName(), "UTF-8"), LINE_1);		
			
			svn.setFileContent(svnTrunkPath, file.getName(), LINE_2, FILE1_CHANGED_COMMIT_MESSAGE);
			
			svnVCS.checkout("file:///" + svnRepoWorkspace.getFolder().getPath() + svnTrunkPath,
					workspace.getFolder());
			
			byte[] data = FileUtils.readFileToByteArray(file);
			String content = new String(data, "UTF-8");
			assertEquals(content, LINE_2);
			
		} finally {
			workspace.unlock();
		}
	}
}
