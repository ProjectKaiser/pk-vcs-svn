package org.scm4j.vcs.svn;

import org.apache.commons.io.FileUtils;
import org.scm4j.vcs.api.*;
import org.scm4j.vcs.api.exceptions.EVCSBranchExists;
import org.scm4j.vcs.api.exceptions.EVCSException;
import org.scm4j.vcs.api.exceptions.EVCSFileNotFound;
import org.scm4j.vcs.api.exceptions.EVCSTagExists;
import org.scm4j.vcs.api.workingcopy.IVCSLockedWorkingCopy;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;
import org.scm4j.vcs.api.workingcopy.IVCSWorkspace;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.auth.SVNPasswordAuthentication;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.*;
import org.tmatesoft.svn.core.wc2.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SVNVCS implements IVCS {
	private static final int SVN_PATH_IS_NOT_WORKING_COPY_ERROR_CODE = 155007;
	private static final int SVN_ITEM_EXISTS_ERROR_CODE = 160020;
	private static final int SVN_FILE_NOT_FOUND_ERROR_CODE = 160013;
	private BasicAuthenticationManager authManager;
	private SVNRepository repository;
	private ISVNOptions options;
	private SVNClientManager clientManager;
	private SVNURL trunkSVNUrl;
	private SVNAuthentication userPassAuth;
	private final IVCSRepositoryWorkspace repo;
	private String repoUrl;
	
	public static final String MASTER_PATH= "trunk/";
	public static final String BRANCHES_PATH = "branches/";
	public static final String TAGS_PATH = "tags/";
	public static final String SVN_VCS_TYPE_STRING = "svn";

	public void setClientManager(SVNClientManager clientManager) {
		this.clientManager = clientManager;
	}

	
	public SVNClientManager getClientManager() {
		return clientManager;
	}
	
	public ISVNOptions getOptions() {
		return options;
	}

	public SVNURL getTrunkSVNUrl() {
		return trunkSVNUrl;
	}

	public SVNRepository getSVNRepository() {
		return repository;
	}

	public void setSVNRepository(SVNRepository repository) {
		this.repository = repository;
	}
	
	public SVNVCS(IVCSRepositoryWorkspace repo, String user, String password) {
		this.repo = repo;
		repoUrl = repo.getRepoUrl().trim();
		if (!repoUrl.endsWith("/") && !repoUrl.endsWith("\\")) {
			repoUrl += "/";
		}
        options = SVNWCUtil.createDefaultOptions(true); 
		try {
			trunkSVNUrl = SVNURL.parseURIEncoded(repo.getRepoUrl().replace("\\", "/"));
			repository = SVNRepositoryFactory.create(trunkSVNUrl);
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
		
		userPassAuth = SVNPasswordAuthentication.newInstance(user, 
				(password == null ? null : password.toCharArray()), true, trunkSVNUrl, false);
		authManager = new BasicAuthenticationManager(new SVNAuthentication[] {userPassAuth});
		repository.setAuthenticationManager(authManager);
		
		clientManager = SVNClientManager.newInstance(
				options, repository.getAuthenticationManager());
	}
	
	public SVNURL getBranchUrl(String branchPath) throws Exception {
		return SVNURL.parseURIEncoded(repoUrl + getBranchName(branchPath));
	}

	@Override
	public void createBranch(String srcBranchName, String dstBranchName, String commitMessage) throws EVCSBranchExists {
		SVNURL fromUrl;
		SVNURL toUrl;
		try {
			fromUrl = getBranchUrl(srcBranchName);
			toUrl = getBranchUrl(dstBranchName);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		createBranch(fromUrl, toUrl, commitMessage);
	}
	
	public void createBranch(SVNURL fromUrl, SVNURL toUrl, String commitMessage) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			checkout(fromUrl, wc.getFolder());

			SVNCopyClient copyClient = new SVNCopyClient(authManager, options);
			SVNCopySource copySource = new SVNCopySource(SVNRevision.WORKING, SVNRevision.WORKING,
					wc.getFolder());
			copySource.setCopyContents(false);

			copyClient.doCopy(new SVNCopySource[] { copySource }, toUrl,
					false, // isMove
					true, // make parents
					true, // failWhenDstExistsb
					commitMessage, // commit message
					null); // SVNProperties

		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_ITEM_EXISTS_ERROR_CODE) {
				throw new EVCSBranchExists(e);
			} 
			throw new EVCSException (e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void deleteBranch(String branchName, String commitMessage) {
		try {
			clientManager
					.getCommitClient()
					.doDelete(new SVNURL[] { getBranchUrl(branchName) }, commitMessage);
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public VCSMergeResult merge(String srcBranchName, String dstBranchName, String commitMessage) {
		SVNDiffClient diffClient = clientManager.getDiffClient();
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			checkout(getBranchUrl(dstBranchName), wc.getFolder());

			DefaultSVNOptions options = (DefaultSVNOptions) diffClient.getOptions();
			final List<String> conflictingFiles = new ArrayList<>();
			options.setConflictHandler(new ISVNConflictHandler() {
				@Override
				public SVNConflictResult handleConflict(SVNConflictDescription conflictDescription)
						throws SVNException {
					conflictingFiles.add(conflictDescription.getMergeFiles().getLocalPath());
					return new SVNConflictResult(SVNConflictChoice.POSTPONE,
							conflictDescription.getMergeFiles().getResultFile());
				}
			});

			SVNRevisionRange range = new SVNRevisionRange(SVNRevision.create(1), SVNRevision.HEAD);
			try {
				diffClient.doMerge(getBranchUrl(srcBranchName),
						SVNRevision.HEAD, Collections.singleton(range),
						wc.getFolder(), SVNDepth.UNKNOWN, true, false, false, false);

				Boolean success = conflictingFiles.isEmpty();

				if (success) {
					clientManager
							.getCommitClient()
							.doCommit(new File[] {wc.getFolder()}, false, commitMessage,
							new SVNProperties(), null, true, true, SVNDepth.INFINITY);
				} else {
					try {
						SVNWCClient wcClient = getRevertClient(options);
						wcClient.doRevert(new File[] {wc.getFolder()}, SVNDepth.INFINITY, null);
					} catch (Exception e) {
						// It doesn't matter if we failed to revert. Just make the workspace corrupted.
						wc.setCorrupted(true);
					}
				}
				return new VCSMergeResult(success, conflictingFiles);
			} catch (Exception e) {
				wc.setCorrupted(true);
				throw e;
			}
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public SVNWCClient getRevertClient(DefaultSVNOptions options) {
		return new SVNWCClient(authManager, options);
	}

	public void checkout(SVNURL sourceUrl, File destPath) throws Exception {
		SVNUpdateClient updateClient = clientManager.getUpdateClient();
		updateClient.setIgnoreExternals(false);
		if (isWorkingCopyInited(destPath)) {
			updateClient.doSwitch(destPath, sourceUrl, SVNRevision.HEAD, 
					SVNRevision.HEAD, SVNDepth.INFINITY, false, false);
		} else {
			updateClient.doCheckout(sourceUrl, destPath, SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.UNKNOWN, false);
		}
	}

	public boolean isWorkingCopyInited(File destPath) {
		try {
			clientManager.getStatusClient().doStatus(destPath, false);
			return true;
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_PATH_IS_NOT_WORKING_COPY_ERROR_CODE) {
				return false;
			} else {
				throw new EVCSException(e);
			}
		}
	}

	@Override
	public void setCredentials(String user, String password) {
		userPassAuth = SVNPasswordAuthentication.newInstance(user, password == null ? null : password.toCharArray(),
				true, trunkSVNUrl, false);
		authManager.setAuthentications(new SVNAuthentication[] {userPassAuth});
	}

	@Override
	public void setProxy(String host, int port, String proxyUser, String proxyPassword) {
		authManager.setProxy(host, port, proxyUser, proxyPassword.toCharArray());
	}


	@Override
	public String getFileContent(String branchName, String filePath, String encoding) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			repository.getFile(new File(getBranchName(branchName), filePath).getPath().replace("\\", "/"),
					-1, new SVNProperties(), baos);
			return baos.toString(encoding);
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_FILE_NOT_FOUND_ERROR_CODE) {
				throw new EVCSFileNotFound(e);
			}
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String getBranchName(String branchName) {
		return branchName == null ? MASTER_PATH : BRANCHES_PATH + branchName;
	}

	@Override
	public String getFileContent(String branchName, String filePath) {
		return getFileContent(branchName, filePath, StandardCharsets.UTF_8.name());
	}

	@Override
	public VCSCommit setFileContent(String branchName, String filePath, String content, String commitMessage) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			checkout(getBranchUrl(branchName), wc.getFolder());
			File file = new File(wc.getFolder(), filePath);
			Boolean needToAdd = !file.exists();
			if (!file.exists()) {
				FileUtils.forceMkdir(file.getParentFile());
				file.createNewFile();
			}

			FileWriter writer = new FileWriter(file);
			writer.write(content);
			writer.close();

			if (needToAdd) {
				clientManager
						.getWCClient()
						.doAdd(file,
								true /* force, avoiding "file is already under version control" exception */,
								false, false, SVNDepth.EMPTY, false, true);
			}

			try {
				SVNCommitInfo newCommit = clientManager
						.getCommitClient()
						.doCommit(new File[] { wc.getFolder() }, false, commitMessage,
								new SVNProperties(), null, false, false, SVNDepth.INFINITY);
				return newCommit == SVNCommitInfo.NULL ? VCSCommit.EMPTY :
					new VCSCommit(Long.toString(newCommit.getNewRevision()), commitMessage, newCommit.getAuthor());
			} catch (SVNException e) {
				wc.setCorrupted(true);
				throw e;
			}
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getRepoUrl() {
		return repo.getRepoUrl();
	}
	
	private List<VCSDiffEntry> fillUnifiedDiffs(final String srcBranchName, final String dstBranchName, List<VCSDiffEntry> entries)
			throws Exception {
		List<VCSDiffEntry> res = new ArrayList<>();
		for (VCSDiffEntry entry : entries) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			
			final SvnOperationFactory svnOperationFactory = new SvnOperationFactory();
			final SvnDiff diff = svnOperationFactory.createDiff();
			
			if (entry.getChangeType() == VCSChangeType.ADD) {
				SVNLogEntry firstCommit = getBranchFirstCommit(dstBranchName);
				diff.setSource(SvnTarget.fromURL(getBranchUrl(srcBranchName).appendPath(entry.getFilePath(), true), SVNRevision.HEAD), 
						SVNRevision.create(firstCommit.getRevision()), 
						SVNRevision.create(repository.info(getBranchName(dstBranchName), -1).getRevision()));
			} else if (entry.getChangeType() == VCSChangeType.DELETE) {
				SVNLogEntry firstCommit = getBranchFirstCommit(dstBranchName);
				diff.setSource(SvnTarget.fromURL(getBranchUrl(dstBranchName).appendPath(entry.getFilePath(), true), SVNRevision.HEAD), 
						SVNRevision.create(repository.info(getBranchName(dstBranchName), -1).getRevision()),
						SVNRevision.create(firstCommit.getRevision()));
			} else {
				diff.setSources(
					SvnTarget.fromURL(getBranchUrl(dstBranchName).appendPath(entry.getFilePath(), true), SVNRevision.HEAD),
					SvnTarget.fromURL(getBranchUrl(srcBranchName).appendPath(entry.getFilePath(), true), SVNRevision.HEAD));
			}
            diff.setOutput(baos);
            diff.run();

			res.add(new VCSDiffEntry(entry.getFilePath(), entry.getChangeType(), baos.toString("UTF-8")));
		}
		return res;
	}

	protected SVNLogEntry getBranchFirstCommit(final String branchPath) throws Exception {
		final List<SVNLogEntry> logEntries = new ArrayList<>();
		repository.log(new String[] { getBranchName(branchPath) }, -1 /* start from head descending */,
				0, true, true, -1, new ISVNLogEntryHandler() {
			@Override
			public void handleLogEntry(SVNLogEntry logEntry) throws SVNException {
				logEntries.add(logEntry);
			}
		});
		return logEntries.get(logEntries.size() - 1);
	}
	
	
	private List<VCSDiffEntry> getDiffEntries(final String srcBranchName, final String dstBranchName)
			throws Exception {
		final SvnOperationFactory svnOperationFactory = new SvnOperationFactory();
		final SvnDiffSummarize summarizeDiff = svnOperationFactory.createDiffSummarize();
		final List<VCSDiffEntry> res = new ArrayList<>();
		
		summarizeDiff.setSources(
				SvnTarget.fromURL(getBranchUrl(dstBranchName), SVNRevision.HEAD),
				SvnTarget.fromURL(getBranchUrl(srcBranchName), SVNRevision.HEAD));
		
		summarizeDiff.setReceiver(new ISvnObjectReceiver<SvnDiffStatus>() {
            public void receive(SvnTarget target, SvnDiffStatus diffStatus) throws SVNException {
            	if (diffStatus.getPath().length() == 0) {
            		return;
            	}
            	VCSDiffEntry entry = new VCSDiffEntry(diffStatus.getPath(),
                		SVNChangeTypeToVCSChangeType(diffStatus.getModificationType()), null);
                res.add(entry);
            }


        });
		summarizeDiff.run();

		return res;
	}

	public VCSChangeType SVNChangeTypeToVCSChangeType(SVNStatusType modificationType) {
		if (SVNStatusType.STATUS_ADDED.equals(modificationType)) {
			return VCSChangeType.ADD;
		} else if (SVNStatusType.STATUS_DELETED.equals(modificationType)) {
			return VCSChangeType.DELETE;
		} else if (SVNStatusType.STATUS_MODIFIED.equals(modificationType)) {
			return VCSChangeType.MODIFY;
		} else {
			return VCSChangeType.UNKNOWN;
		}
	}

	@Override
	public List<VCSDiffEntry> getBranchesDiff(final String srcBranchName, final String dstBranchName) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			checkout(getBranchUrl(dstBranchName), wc.getFolder());
			List<VCSDiffEntry> entries = getDiffEntries(srcBranchName, dstBranchName);
			entries = fillUnifiedDiffs(srcBranchName, dstBranchName, entries);
			return entries;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Set<String> getBranches(String path) {
		try {
			List<String> entries = new ArrayList<>();
			listEntries(entries, SVNVCS.BRANCHES_PATH, path);
			Set<String> res = new HashSet<>(entries);
			addTrunkIfExists(res);
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	protected void addTrunkIfExists(Set<String> res) {
		try {
			if (repository.checkPath(MASTER_PATH, -1) == SVNNodeKind.DIR) {
				res.add(MASTER_PATH.replace("/", ""));
			}
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}

	protected void listEntries(List<String> entries, String path, String subdir) throws Exception {
		
		if (repository.checkPath(path + subdir ,  -1) == SVNNodeKind.NONE) {
			return;
		}
		@SuppressWarnings("unchecked")
		Collection<SVNDirEntry> subEntries = repository.getDir(path + subdir, -1, null, (Collection<SVNDirEntry>) null);
		List<SVNDirEntry> list = new ArrayList<>(subEntries);
		Collections.sort(list, new Comparator<SVNDirEntry>() {
			@Override
			public int compare(SVNDirEntry o1, SVNDirEntry o2) {
				if (o1.getRevision() < o2.getRevision()) {
					return -1;
				}
				if (o1.getRevision() > o2.getRevision()) {
					return 1;
				}
				return 0;
			}
		});
		for (SVNDirEntry entry : list) {
			if (entry.getKind() == SVNNodeKind.DIR) {
				entries.add(((path.equals(SVNVCS.BRANCHES_PATH) ? "" : path) + subdir + entry.getName())
						.replace(SVNVCS.BRANCHES_PATH, ""));
			}
		}
	}
	
	@Override
	public List<VCSCommit> log(String branchName, int limit) {
		final List<VCSCommit> res = new ArrayList<>();
		try {
			getBranchUrl(branchName); // for exception test only
			repository.log(new String[] { getBranchName(branchName) }, 
					-1L /* start from head descending */, 
					0L, true, true, limit, new ISVNLogEntryHandler() {
				@Override
				public void handleLogEntry(SVNLogEntry logEntry) throws SVNException {
					res.add(svnLogEntryToVCSCommit(logEntry));
				}
			});
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getVCSTypeString() {
		return SVN_VCS_TYPE_STRING;
	}

	@Override
	public VCSCommit removeFile(String branchName, String filePath, String commitMessage) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			SVNCommitInfo res = clientManager
					.getCommitClient()
					.doDelete(new SVNURL[] {getBranchUrl(branchName).appendPath(filePath, true)}, commitMessage);
			return new VCSCommit(Long.toString(res.getNewRevision()), commitMessage, res.getAuthor());
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
	}
	
	@Override
	public List<VCSCommit> getCommitsRange(String branchName, String firstCommitId, WalkDirection direction, int limit) {
		final List<VCSCommit> res = new ArrayList<>();
		try {
			Long sinceCommit;
			Long untilCommit;
			if (direction == WalkDirection.ASC) {
				sinceCommit = firstCommitId == null ? getBranchFirstCommit(branchName).getRevision() :
					Long.parseLong(firstCommitId);
				untilCommit = Long.parseLong(getHeadCommit(branchName).getRevision());
			} else {
				sinceCommit = firstCommitId == null ? Long.parseLong(getHeadCommit(branchName).getRevision()) :
					Long.parseLong(firstCommitId);
				untilCommit = getBranchFirstCommit(branchName).getRevision();
			}
			repository.log(new String[] { getBranchName(branchName) }, sinceCommit, untilCommit, true, true, limit,
					new ISVNLogEntryHandler() {
				@Override
				public void handleLogEntry(SVNLogEntry logEntry) throws SVNException {
					VCSCommit commit = svnLogEntryToVCSCommit(logEntry);
					res.add(commit);
				}
			});
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private VCSCommit svnLogEntryToVCSCommit(SVNLogEntry logEntry) {
		return new VCSCommit(Long.toString(logEntry.getRevision()), logEntry.getMessage(),
				logEntry.getAuthor());
	}

	@Override
	public List<VCSCommit> getCommitsRange(String branchName, String firstCommitId, String untilCommitId) {
		final List<VCSCommit> res = new ArrayList<>();
		try {
			Long sinceCommit = firstCommitId == null ?
					getBranchFirstCommit(branchName).getRevision() :
					Long.parseLong(firstCommitId);
			Long untilCommit = untilCommitId == null ? -1L : Long.parseLong(untilCommitId);
			repository.log(new String[] { getBranchName(branchName) }, sinceCommit, untilCommit, true, true, 0 /* limit */,
					new ISVNLogEntryHandler() {
				@Override
				public void handleLogEntry(SVNLogEntry logEntry) throws SVNException {
					res.add(svnLogEntryToVCSCommit(logEntry));
				}
			});
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public IVCSWorkspace getWorkspace() {
		return repo.getWorkspace();
	}

	@Override
	public VCSCommit getHeadCommit(String branchName) {
		try {
			SVNLogEntry entry = revToSVNEntry(getBranchName(branchName), -1L);
			if (entry != null) {
				return svnLogEntryToVCSCommit(entry);
			}
			return null;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String toString() {
		return "SVNVCS [url=" + repo.getRepoUrl() + "]";
	}

	@Override
	public Boolean fileExists(String branchName, String filePath) {
		try {
			SVNNodeKind nodeKind = repository.checkPath(
					new File(getBranchName(branchName), filePath).getPath().replace("\\", "/") ,  -1 );
			return nodeKind == SVNNodeKind.FILE;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public VCSTag createTag(String branchName, String tagName, String tagMessage) throws EVCSTagExists {
		try {
			SVNURL srcURL = getBranchUrl(branchName); 
			SVNURL dstURL = SVNURL.parseURIEncoded(repoUrl + TAGS_PATH + tagName); 
			SVNCopySource copySource = 
					new SVNCopySource(SVNRevision.HEAD, SVNRevision.HEAD, srcURL); 

			clientManager.getCopyClient().doCopy(new SVNCopySource[] {copySource}, dstURL, 
			        false, false, true, tagMessage, null);
			
			SVNDirEntry entry = repository.info(TAGS_PATH + tagName, -1);
			
			VCSTag tag = new VCSTag(tagName, tagMessage, entry.getAuthor(), getHeadCommit(branchName));
			return tag;
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_ITEM_EXISTS_ERROR_CODE) {
				throw new EVCSTagExists(e);
			} 
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	protected SVNLogEntry revToSVNEntry(String branchName, Long rev) throws Exception {
		SVNDirEntry info = repository.info(branchName, rev);
		@SuppressWarnings("unchecked")
		Collection<SVNLogEntry> entries = repository.log(new String[] {branchName}, null, info.getRevision(), info.getRevision(), true, true);
		if (entries != null) {
			return entries.iterator().next();
		}
		return null;
	}
	
	private class SVNTagBaseCommit implements ISVNLogEntryHandler {
		
		public Long copyFromRevision;

		@Override
		public void handleLogEntry(SVNLogEntry logEntry) throws SVNException {
			for (String s : logEntry.getChangedPaths().keySet()) {
				SVNLogEntryPath entryPath = logEntry.getChangedPaths().get(s);
				copyFromRevision = entryPath.getCopyRevision();
			}
		}
	}
	
	@Override
	public List<VCSTag> getTags() {
		List<String> entries = new ArrayList<>();
		try {
			listEntries(entries, TAGS_PATH, "");
			List<VCSTag> res = new ArrayList<>();
			SVNTagBaseCommit handler;
			for (String entryStr : entries) {
				
				SVNLogEntry entry = revToSVNEntry(entryStr, -1L);

				handler = new SVNTagBaseCommit();
				
				repository.log(new String[] { entryStr }, -1 /* start from head descending */,
						0, true, true, -1, handler);
				
				SVNDirEntry copyFromEntry = repository.info("", handler.copyFromRevision);
				
				res.add(new VCSTag(entryStr.replace(TAGS_PATH, ""), entry.getMessage(), entry.getAuthor(), new VCSCommit(Long.toString(copyFromEntry.getRevision()),
						copyFromEntry.getCommitMessage(), copyFromEntry.getAuthor())));
			}
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public VCSTag getLastTag() {
		try {
			SVNLogEntry entry = revToSVNEntry(TAGS_PATH, -1L);
			
			Long copyFromRevision = -1L;
			for (String s : entry.getChangedPaths().keySet()) {
				SVNLogEntryPath entryPath = entry.getChangedPaths().get(s);
				copyFromRevision = entryPath.getCopyRevision();
			}
			
			SVNLogEntry copyFromEntry = revToSVNEntry("", copyFromRevision);
			String tagName = "";
			for (String name : revToSVNEntry(TAGS_PATH, -1L).getChangedPaths().keySet()) {
				tagName = name.replace("/" + TAGS_PATH, "") ;
			}
			
			return new VCSTag(tagName, entry.getMessage(), entry.getAuthor(), svnLogEntryToVCSCommit(copyFromEntry));
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void removeTag(String tagName) {
		try {
			clientManager
					.getCommitClient()
					.doDelete(new SVNURL[] { SVNURL.parseURIEncoded(repoUrl + TAGS_PATH + tagName) }, null);
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}


	@Override
	public void checkout(String branchName, String targetPath) {
		try {
			checkout(getBranchUrl(branchName), new File(targetPath));
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
