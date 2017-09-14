package org.scm4j.vcs.svn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.exceptions.verification.WantedButNotInvoked;
import org.scm4j.vcs.api.IVCS;
import org.scm4j.vcs.api.VCSChangeType;
import org.scm4j.vcs.api.WalkDirection;
import org.scm4j.vcs.api.abstracttest.VCSAbstractTest;
import org.scm4j.vcs.api.exceptions.EVCSException;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;
import org.tmatesoft.svn.core.ISVNLogEntryHandler;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNProxyManager;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.auth.SVNPasswordAuthentication;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNCommitClient;
import org.tmatesoft.svn.core.wc.SVNStatusClient;
import org.tmatesoft.svn.core.wc.SVNStatusType;
import org.tmatesoft.svn.core.wc.SVNWCClient;

public class SVNVCSTest extends VCSAbstractTest {

	private static final String FOLDER_STRUCT_CREATED_COMMIT_MESSAGE = "trunk/ and branches/ created";
	private final RuntimeException testSvnRevertException = new RuntimeException("test exeption on svn revert");
	private final SVNException testSVNException = new SVNException(SVNErrorMessage.create(SVNErrorCode.ATOMIC_INIT_FAILURE, "test svn exception"));
	private final Exception testCommonException = new Exception("test exception");
	private SVNVCS svn;
	private SVNRepository svnRepo;
	private SVNWCClient mockedSVNRevertClient;

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
	
	public void testAuth(SVNVCS svn, String user, String pass) throws Exception {
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
	public void testExceptions() throws Exception {
		for (Method m : IVCS.class.getDeclaredMethods()) {
			Object[] params = new Object[m.getParameterTypes().length];
			Integer i = 0;
			for (Class<?> clazz : m.getParameterTypes()) {
				params[i] = clazz.isPrimitive() ? 0: null;
				i++;
			}
			testExceptionThrowing(testSVNException, m, params);

			testExceptionThrowing(testCommonException, m, params);
		}
	}

	private void testExceptionThrowing(Exception testException, Method m, Object[] params) throws Exception {
		reset(svn);
		doThrow(testException).when(svn).checkout(any(SVNURL.class), any(File.class), (String) isNull());
		doThrow(testException).when(svn).getBranchUrl(null);
		doThrow(testException).when(svn).listEntries(anyString(), anyString());
		doThrow(testException).when(svn).getBranchFirstCommit(null);
		doThrow(testException).when(svn).revToSVNEntry(anyString(), any(Long.class));
		try {
			m.invoke(vcs, params); 
			if (wasMockedMethodInvoked()) {
				fail();
			}
		} catch (InvocationTargetException e) {
			if (wasMockedMethodInvoked()) {
				assertTrue(e.getCause() instanceof RuntimeException);
				assertTrue(e.getCause().getMessage().contains(testException.getMessage()));
			}
		} catch (Exception e) {
			if (wasMockedMethodInvoked()) {
				fail();
			}
		}
	}

	private boolean wasMockedMethodInvoked() throws Exception {
		try {
			verify(svn).checkout(any(SVNURL.class), any(File.class), (String) isNull());
			return true;
		} catch (WantedButNotInvoked e1) {
		}
		try {
			verify(svn).getBranchUrl(null);
			return true;
		} catch (WantedButNotInvoked e1) {
		}
		try {
			verify(svn).listEntries(anyString(), anyString());
			return true;
		} catch (WantedButNotInvoked e1) {
		}
		try {
			verify(svn).getBranchFirstCommit(null);
			return true;
		} catch (WantedButNotInvoked e1) {
		}
		try {
			verify(svn).revToSVNEntry(anyString(), any(Long.class));
			return true;
		} catch (WantedButNotInvoked e1) {
		}
		return false;
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
		doThrow(testCommonException).when(svn).getBranchUrl("test");
		try {
			vcs.createBranch("", "", "");
			fail();
		} catch (RuntimeException e) {
			checkCommonException(e);
		}
		doThrow(testSVNException).when(svn).getBranchUrl("test");
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
		doThrow(testCommonException).when(svn).getBranchUrl(anyString());
		try {
			vcs.merge(NEW_BRANCH, null, MERGE_COMMIT_MESSAGE);
			fail();
		} catch (RuntimeException e) {
			checkCommonException(e);
		}
		assertTrue(mockedLWC.getCorrupted());
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
			vcs.getFileContent("", "", "");
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
		assertTrue(e.getCause() instanceof SVNException);
		assertTrue(e.getCause().getMessage().contains(testSVNException.getMessage()));
	}

	private void checkCommonException(RuntimeException e) {
		assertTrue(e.getCause() instanceof Exception);
		assertTrue(e.getCause().getMessage().contains(testCommonException.getMessage()));
	}

	@Test
	public void testGetCommitsRangeExceptions() throws Exception {
		SVNRepository mockedRepo = spy(svn.getSVNRepository());
		svn.setSVNRepository(mockedRepo);
		doThrow(testSVNException).when(mockedRepo).log(any(String[].class), anyLong(), anyLong(), anyBoolean(), anyBoolean(),
				any(Integer.class), any(ISVNLogEntryHandler.class));
		doThrow(testCommonException).when(svn).getBranchFirstCommit(anyString());
		try {
			vcs.getCommitsRange("", null, null);
			fail();
		} catch (RuntimeException e) {
			checkCommonException(e);
		}

		try {
			vcs.getCommitsRange(null, null, WalkDirection.ASC, 0);
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
		}
	}

	@Test
	public void testGetHeadCommitExceptions() throws Exception {
		SVNRepository mockedRepo = spy(svn.getSVNRepository());
		svn.setSVNRepository(mockedRepo);
		doThrow(testSVNException).when(mockedRepo).log(any(String[].class), anyLong(), anyLong(), anyBoolean(), anyBoolean(),
				any(Integer.class), any(ISVNLogEntryHandler.class));
		try {
			vcs.getHeadCommit("");
			fail();
		} catch (EVCSException e) {
			checkEVCSException(e);
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

	@Test
	public void testListEntriesSorting() throws Exception {
		SVNRepository mockedRepo = spy(svn.getSVNRepository());
		svn.setSVNRepository(mockedRepo);
		SVNDirEntry entry1 = new SVNDirEntry(null, null, "entry1", SVNNodeKind.DIR, 0, false, 1, null, null);
		SVNDirEntry entry2 = new SVNDirEntry(null, null, "entry2", SVNNodeKind.DIR, 0, false, 2, null, null);

		doReturn(Arrays.asList(entry1, entry2)).when(mockedRepo).getDir(anyString(), anyLong(), any(SVNProperties.class),
				Matchers.<Collection<SVNDirEntry>>any());

		List<String> entries = svn.listEntries("", "");
		assertEquals(entries.get(0), entry1.getName());
		assertEquals(entries.get(1), entry2.getName());
		doReturn(Arrays.asList(entry1, entry1)).when(mockedRepo).getDir(anyString(), anyLong(), any(SVNProperties.class),
				Matchers.<Collection<SVNDirEntry>>any());
		entries = svn.listEntries("", "");
		assertEquals(entries.get(0), entry1.getName());
		assertEquals(entries.get(1), entry1.getName());
	}

	@Test
	public void testGetHeadCommitNull() throws Exception {
		doReturn(null).when(svn).revToSVNEntry(anyString(), any(Long.class));
		assertNull(vcs.getHeadCommit(""));
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
		doReturn(SVNNodeKind.NONE).when(mockedRepo).checkPath((String) isNull(), anyLong());
		svn.listEntries(null, null); // expecting no NPE 
	}
}
