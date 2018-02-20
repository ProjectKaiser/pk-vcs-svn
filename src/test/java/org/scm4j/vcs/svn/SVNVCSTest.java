package org.scm4j.vcs.svn;

import org.junit.After;
import org.junit.Test;
import org.mockito.Matchers;
import org.scm4j.vcs.api.IVCS;
import org.scm4j.vcs.api.VCSChangeType;
import org.scm4j.vcs.api.WalkDirection;
import org.scm4j.vcs.api.abstracttest.VCSAbstractTest;
import org.scm4j.vcs.api.exceptions.EVCSException;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.auth.ISVNProxyManager;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.auth.SVNPasswordAuthentication;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.anyCollectionOf;
import static org.mockito.Mockito.*;

public class SVNVCSTest extends VCSAbstractTest {

	private static final String FOLDER_STRUCT_CREATED_COMMIT_MESSAGE = "trunk/ and branches/ created";
	private final RuntimeException testSvnRevertException = new RuntimeException("test exeption on svn revert");
	private final SVNException testSVNException = new SVNException(SVNErrorMessage.create(SVNErrorCode.ATOMIC_INIT_FAILURE, "test svn exception"));
	private final IOException testCommonException = new IOException("test exception");
	private SVNVCS svn;
	private SVNRepository svnRepo;private SVNWCClient mockedSVNRevertClient;

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
	protected IVCS getVCS(IVCSRepositoryWorkspace mockedVCSRepo) {
		// nulls as user and pwd because we using file repository, not server
		svn = spy(new SVNVCS(mockedVCSRepo, null, null));
		return svn;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void setMakeFailureOnVCSReset(Boolean doMakeFailure) {
		if (doMakeFailure) {
			mockedSVNRevertClient = spy(svn.getRevertClient((DefaultSVNOptions) svn.getOptions()));
			doReturn(mockedSVNRevertClient).when(svn).getRevertClient(any(DefaultSVNOptions.class));
			try {
				doThrow(testSvnRevertException).when(mockedSVNRevertClient)
						.doRevert(any(File[].class), any(SVNDepth.class),
								isNull(Collection.class));
			} catch (SVNException e) {
				throw new RuntimeException(e);
			}
		} else {
			if (mockedSVNRevertClient != null) {
				doCallRealMethod().when(svn).getRevertClient((DefaultSVNOptions) svn.getOptions());
				mockedSVNRevertClient = null;
			}
		}
	}

	@Test
	public void testSVNVCSCreation() {
		IVCSRepositoryWorkspace mockedWS = mock(IVCSRepositoryWorkspace.class);
		doReturn("wrong_protocol://www.ru").when(mockedWS).getRepoUrl();
		try {
			new SVNVCS(mockedWS, "", "");
			fail();
		} catch (EVCSException e) {
			assertTrue(e.getCause() instanceof SVNException);
			assertTrue(e.getMessage().contains(e.getCause().getMessage()));
		}
	}

	@Test
	public void testCredentials() throws Exception {
		testAuth(new SVNVCS(localVCSRepo, null, null), null, null);
		testAuth(new SVNVCS(localVCSRepo, "user", "pass"), "user", "pass");
		vcs.setCredentials(null, null);
		testAuth(svn, null, null);
		vcs.setCredentials("user", "pass");
		testAuth(svn, "user", "pass");
	}
	
	private void testAuth(SVNVCS svn, String user, String pass) throws Exception {
		SVNRepository repo = svn.getSVNRepository();
		SVNAuthentication auth = repo.getAuthenticationManager().getFirstAuthentication("svn.simple", "",
				svn.getTrunkSVNUrl());
		assertTrue(auth instanceof SVNPasswordAuthentication);
		SVNPasswordAuthentication pAuth = (SVNPasswordAuthentication) auth;
		assertEquals(pAuth.getUserName(), user);
		if (pass == null) {
			assertTrue(pAuth.getPasswordValue().length == 0);
		} else {
			assertEquals(new String(pAuth.getPasswordValue()), pass);
		}
	}

	@Test
	public void testSVNExceptions() throws SVNException {
		doThrow(testSVNException).when(svn).getBranchUrl(anyString());
		doThrow(testSVNException).when(svn).listEntries(anyString());
		doThrow(testSVNException).when(svn).getBranchFirstCommit(anyString());
		doThrow(testSVNException).when(svn).getDirHeadLogEntry(anyString());
		testSVNException(() -> svn.createBranch("", "", ""));
		testSVNException(() -> svn.deleteBranch("", ""));
		testSVNException(() -> svn.merge("", "", ""));
		testSVNException(() -> svn.setFileContent("", "", "", ""));
		testSVNException(() -> svn.getBranchesDiff("", ""));
		testSVNException(() -> svn.getBranches(""));
		testSVNException(() -> svn.log("", 0));
		testSVNException(() -> svn.removeFile("", "", ""));
		testSVNException(() -> svn.getCommitsRange("", null, WalkDirection.ASC, 0));
		testSVNException(() -> svn.getCommitsRange("", null, ""));
		testSVNException(() -> svn.getHeadCommit(""));
		testSVNException(() -> svn.createTag("", "", "", ""));
		testSVNException(() -> svn.checkout("", "", ""));
	}

	@Test
	public void testCommonExceptions() throws IOException {
		IVCSRepositoryWorkspace mockedRepo = mock(IVCSRepositoryWorkspace.class);
		svn.setRepo(mockedRepo);
		doThrow(testCommonException).when(mockedRepo).getVCSLockedWorkingCopy();
		testCommonException(() -> svn.setFileContent("", "", "", ""));
		testCommonException(() -> svn.merge("", "", ""));
		testCommonException(() -> svn.getBranchesDiff("", ""));
	}

	private void testSVNException(Runnable toTest) {
		testException(toTest, testSVNException);
	}

	private void testException(Runnable toTest, Exception expectedCause) {
		try {
			toTest.run();
			fail();
		} catch (Exception e) {
			checkException(e, expectedCause);
		}
	}

	private void testCommonException(Runnable toTest) {
		testException(toTest, testCommonException);
	}

	@Test
	public void testVCSTypeString() {
		assertEquals(vcs.getVCSTypeString(), SVNVCS.SVN_VCS_TYPE_STRING);
	}

	@Test
	public void testDefaultChangeTypeToVCSType() throws IllegalAccessException {
		for (Field f : SVNStatusType.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
				if (!f.get(null).equals(SVNStatusType.STATUS_ADDED)
						&& !f.get(null).equals(SVNStatusType.STATUS_DELETED)
						&& !f.get(null).equals(SVNStatusType.STATUS_MODIFIED)) {
					assertEquals(svn.SVNChangeTypeToVCSChangeType((SVNStatusType) f.get(null)),
							VCSChangeType.UNKNOWN);
				}
			}
		}
	}

	@Test
	public void testCreateBranchExceptions() throws Exception {
		SVNClientManager mockedManager = spy(svn.getClientManager());
		SVNCopyClient mockedCopyClient = mock(SVNCopyClient.class);
		svn.setClientManager(mockedManager);
		doReturn(mockedCopyClient).when(mockedManager).getCopyClient();
		doThrow(testSVNException).when(mockedCopyClient).doCopy(any(SVNCopySource[].class), any(SVNURL.class),
				anyBoolean(), anyBoolean(), anyBoolean(), anyString(), any(SVNProperties.class));
		try {
			vcs.createBranch("", "", "");
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
	}

	@Override
	public void testMergeConflictWCCorruption() throws Exception {
		super.testMergeConflictWCCorruption();
		super.resetMocks();
		setMakeFailureOnVCSReset(false);
		SVNWCClient mockedWCClient = mock(SVNWCClient.class);
		doReturn(mockedWCClient).when(svn).getRevertClient(any(DefaultSVNOptions.class));
		doThrow(testSVNException).when(mockedWCClient).doRevert(any(File[].class), any(SVNDepth.class),
				Matchers.<Collection<String>>any());
		vcs.merge(NEW_BRANCH, null, MERGE_COMMIT_MESSAGE);
		assertTrue(mockedLWC.getCorrupted());

		SVNClientManager mockedManager = spy(svn.getClientManager());
		SVNDiffClient mockedDiffClient = spy(mockedManager.getDiffClient());
		svn.setClientManager(mockedManager);
		doReturn(mockedDiffClient).when(mockedManager).getDiffClient();
		doThrow(testSVNException).when(mockedDiffClient).doMerge(any(SVNURL.class), any(SVNRevision.class),
				anyCollectionOf(SVNRevisionRange.class), any(File.class), any(SVNDepth.class),
				anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
		try {
			vcs.merge(NEW_BRANCH, null, MERGE_COMMIT_MESSAGE);
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
	}

	@Test
	public void testIsWorkingCopyInitedExceptions() throws Exception {
		SVNStatusClient mockedStatus = mock(SVNStatusClient.class);
		svn.setClientManager(spy(svn.getClientManager()));
		SVNClientManager manager = svn.getClientManager();
		doReturn(mockedStatus).when(manager).getStatusClient();
		doThrow(testSVNException).when(mockedStatus).doStatus(any(File.class), anyBoolean());
		try {
			svn.isWorkingCopyInited(null);
			fail();
		} catch (EVCSException e) {
			assertTrue(e.getCause() instanceof SVNException);
		}
	}

	@Test
	public void testProxy() throws Exception {
		vcs.setProxy("host", 123, "user", "pass");
		ISVNProxyManager manager = svn.getSVNRepository().getAuthenticationManager().getProxyManager(svn.getTrunkSVNUrl());
		assertEquals(manager.getProxyHost(), "host");
		assertEquals(manager.getProxyPassword(), "pass");
		assertEquals(manager.getProxyPort(), 123);
		assertEquals(manager.getProxyUserName(), "user");
	}
	
	@Test
	public void testGetFileContentExceptions() throws Exception {
		SVNRepository mockedRepo = spy(svn.getSVNRepository());
		svn.setSVNRepository(mockedRepo);
		doThrow(testSVNException).when(mockedRepo).getFile(anyString(), anyLong(), any(SVNProperties.class), any(OutputStream.class));
		try {
			vcs.getFileContentFromBranch("", "");
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}

		SVNRepository repo = svn.getSVNRepository();
		svn.setSVNRepository(null);
		try {
			vcs.getFileContentFromBranch("", "");
			fail();
		} catch (RuntimeException e) {
			assertTrue(e.getCause() instanceof NullPointerException);
		}
		svn.setSVNRepository(repo);

		doCallRealMethod().when(mockedRepo).getFile(anyString(), anyLong(), any(SVNProperties.class), any(OutputStream.class));
		doThrow(testSVNException).when(mockedRepo).checkPath(anyString(), anyLong());
		try {
			vcs.getFileContentFromBranch("wrong-branch", "");
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
	}

	@Test
	public void setFileContentWCCorruption() throws Exception {
		SVNCommitClient mockedCommitClient = mock(SVNCommitClient.class);
		svn.setClientManager(spy(svn.getClientManager()));
		SVNClientManager manager = svn.getClientManager();
		doReturn(mockedCommitClient).when(manager).getCommitClient();
		doThrow(testSVNException).when(mockedCommitClient).doCommit(any(File[].class),
				anyBoolean(), anyString(), any(SVNProperties.class), any(String[].class),
				anyBoolean(), anyBoolean(), any(SVNDepth.class));
		try {
			vcs.setFileContent(null, "test.txt", "", "");
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
		assertTrue(mockedLWC.getCorrupted());
	}

	private void checkEVCSException(EVCSException e) {
		checkException(e, testSVNException);
	}

	private void checkException(Exception e, Exception expectedCause) {
		assertEquals(expectedCause.getClass(), e.getCause().getClass());
		if (e.getCause().getMessage() == null) {
			assertNull(expectedCause.getMessage());
		} else {
			assertTrue(e.getCause().getMessage().contains(expectedCause.getMessage()));
		}
	}

	@Test
	public void testFileExistsExceptions() throws Exception {
		SVNRepository mockedRepo = spy(svn.getSVNRepository());
		svn.setSVNRepository(mockedRepo);
		doThrow(testSVNException).when(mockedRepo).checkPath(anyString(), anyLong());
		try {
			vcs.fileExists("", "");
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testListEntriesSorting() throws Exception {
		SVNRepository mockedRepo = spy(svn.getSVNRepository());
		svn.setSVNRepository(mockedRepo);
		SVNDirEntry entry1 = new SVNDirEntry(null, null, "entry1", SVNNodeKind.DIR, 0, false, 1, null, null);
		SVNDirEntry entry2 = new SVNDirEntry(null, null, "entry2", SVNNodeKind.DIR, 0, false, 2, null, null);

		doReturn(Arrays.asList(entry1, entry2)).when(mockedRepo).getDir(anyString(), anyLong(), (SVNProperties) isNull(),
				(Collection<SVNDirEntry>) isNull());

		List<String> entries = svn.listEntries("");
		assertEquals(entry1.getName(), entries.get(0));
		assertEquals(entry2.getName(), entries.get(1));
		doReturn(Arrays.asList(entry1, entry1)).when(mockedRepo).getDir(anyString(), anyLong(), any(SVNProperties.class),
				Matchers.<Collection<SVNDirEntry>>any());
		entries = svn.listEntries("");
		assertEquals(entry1.getName(), entries.get(0));
		assertEquals(entry1.getName(), entries.get(1));
	}

	@Test
	public void testRevToSVNEntryNull() throws Exception {
		SVNRepository mockedRepo = spy(svn.getSVNRepository());
		svn.setSVNRepository(mockedRepo);
		doReturn(null).when(mockedRepo).log(any(String[].class),
				any(Collection.class), anyLong(), anyLong(), anyBoolean(), anyBoolean());
		assertNull(svn.revToSVNEntry("", -1L));
	}

	@Test
	public void testSVNVCSUtilsCreation() {
		assertNotNull(new SVNVCSUtils());
	}
	
	@Test
	public void testListEntriesNone() throws Exception {
		SVNRepository mockedRepo = spy(svn.getSVNRepository());
		svn.setSVNRepository(mockedRepo);
		doReturn(SVNNodeKind.NONE).when(mockedRepo).checkPath(anyString(), anyLong());
		assertTrue(svn.listEntries(null).isEmpty()); // expecting no NPE
	}
	
	@Test
	public void testGetTagsOnRevisionNoTagsDir() throws SVNException {
		svn.getClientManager()
				.getCommitClient()
				.doDelete(new SVNURL[] { SVNURL.parseURIEncoded(svn.getRepoUrl() + "/" + SVNVCS.TAGS_PATH)}, "tags/ deleted");
		assertTrue(vcs.getTagsOnRevision("0").isEmpty());
	}
	
	@Test
	public void testGetTagsOnRevisionExceptions() throws Exception {
		doThrow(testSVNException).when(svn).getTags(anyString());
		try {
			vcs.getTagsOnRevision("");
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
	}
	
	@Test
	public void testGetTagsExceptions() throws Exception {
		doThrow(testSVNException).when(svn).getTags((String) isNull());
		try {
			vcs.getTags();
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
	}

	@Test
	public void testRemoveTagExceptions() throws SVNException {
		SVNClientManager mockedManager = spy(svn.getClientManager());
		SVNCommitClient mockedCommitClient = mock(SVNCommitClient.class);
		svn.setClientManager(mockedManager);
		doReturn(mockedCommitClient).when(mockedManager).getCommitClient();
		doThrow(testSVNException).when(mockedCommitClient).doDelete(any(SVNURL[].class), (String) isNull());
		try {
			vcs.removeTag("");
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
	}
}
