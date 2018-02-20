package org.scm4j.vcs.svn;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.scm4j.vcs.api.*;
import org.scm4j.vcs.api.exceptions.*;
import org.scm4j.vcs.api.workingcopy.IVCSLockedWorkingCopy;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.auth.SVNPasswordAuthentication;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.*;
import org.tmatesoft.svn.core.wc2.SvnDiff;
import org.tmatesoft.svn.core.wc2.SvnDiffSummarize;
import org.tmatesoft.svn.core.wc2.SvnOperationFactory;
import org.tmatesoft.svn.core.wc2.SvnTarget;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SVNVCS implements IVCS {
	private static final int SVN_PATH_IS_NOT_WORKING_COPY_ERROR_CODE = 155007;
	private static final int SVN_ITEM_EXISTS_ERROR_CODE = 160020;
	private static final int SVN_FILE_NOT_FOUND_ERROR_CODE = 160013;

	public static final String MASTER_PATH= "trunk/";
	public static final String BRANCHES_PATH = "branches/";
	public static final String TAGS_PATH = "tags/";
	public static final String SVN_VCS_TYPE_STRING = "svn";

	private BasicAuthenticationManager authManager;
	private SVNRepository repository;
	private final ISVNOptions options;
	private SVNClientManager clientManager;
	private SVNURL trunkSVNUrl;
	private SVNAuthentication userPassAuth;
	private IVCSRepositoryWorkspace repo;
	private String repoUrl;

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

	public void setRepo(IVCSRepositoryWorkspace repo) {
		this.repo = repo;
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
	
	public SVNURL getBranchUrl(String branchPath) throws SVNException {
		return SVNURL.parseURIEncoded(repoUrl + getBranchName(branchPath));
	}

	@Override
	public void createBranch(String srcBranchName, String dstBranchName, String commitMessage) throws EVCSBranchExists {
		try {
			SVNURL fromUrl = getBranchUrl(srcBranchName);
			SVNURL toUrl = getBranchUrl(dstBranchName);
			SVNCopyClient copyClient = clientManager.getCopyClient();
			SVNCopySource copySource = new SVNCopySource(SVNRevision.HEAD, SVNRevision.HEAD, fromUrl);
			copySource.setCopyContents(false);
			copyClient.doCopy(new SVNCopySource[] { copySource }, toUrl,
					false, // isMove
					true, // make parents
					true, // failWhenDstExists
					commitMessage, // commit message
					null); // SVNProperties

		} catch (SVNException e) {
		if (e.getErrorMessage().getErrorCode().getCode() == SVN_ITEM_EXISTS_ERROR_CODE) {
			throw new EVCSBranchExists(dstBranchName);
		}
		throw new EVCSException(e);
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
		}
	}
	
	@Override
	public VCSMergeResult merge(String srcBranchName, String dstBranchName, String commitMessage) {
		SVNDiffClient diffClient = clientManager.getDiffClient();
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			checkout(getBranchUrl(dstBranchName), wc.getFolder(), null);

			DefaultSVNOptions options = (DefaultSVNOptions) diffClient.getOptions();
			final List<String> conflictingFiles = new ArrayList<>();
			options.setConflictHandler(conflictDescription -> {
				conflictingFiles.add(conflictDescription.getMergeFiles().getLocalPath());
				return new SVNConflictResult(SVNConflictChoice.POSTPONE,
						conflictDescription.getMergeFiles().getResultFile());
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

	SVNWCClient getRevertClient(DefaultSVNOptions options) {
		return new SVNWCClient(authManager, options);
	}

	private void checkout(SVNURL sourceUrl, File destPath, String revision) throws SVNException {
		SVNUpdateClient updateClient = clientManager.getUpdateClient();
		updateClient.setIgnoreExternals(false);
		SVNRevision svnRevision = revision == null ? SVNRevision.HEAD : SVNRevision.parse(revision);
		if (isWorkingCopyInited(destPath)) {
			updateClient.doSwitch(destPath, sourceUrl, svnRevision, svnRevision, SVNDepth.INFINITY, false, false);
		} else {
			updateClient.doCheckout(sourceUrl, destPath, svnRevision, svnRevision, SVNDepth.UNKNOWN, false);
		}
	}

	public boolean isWorkingCopyInited(File destPath) {
		try {
			clientManager.getStatusClient().doStatus(destPath, false);
			return true;
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_PATH_IS_NOT_WORKING_COPY_ERROR_CODE) {
				return false;
			}
			throw new EVCSException(e);
		}
	}

	@Override
	public void setCredentials(String user, String password) {
		userPassAuth = SVNPasswordAuthentication.newInstance(user, password == null ? null : password.toCharArray(),
				true, trunkSVNUrl, false);
		authManager.setAuthentications(new SVNAuthentication[] {userPassAuth});
		clientManager = SVNClientManager.newInstance(
				options, repository.getAuthenticationManager());
	}

	@Override
	public void setProxy(String host, int port, String proxyUser, String proxyPassword) {
		authManager.setProxy(host, port, proxyUser, proxyPassword.toCharArray());
	}

	@Override
	public String getFileContentFromBranch(String branchName, String filePath) throws EVCSFileNotFound {
		return getFileContent(branchName, filePath, null);
	}

	@Override
	public String getFileContentFromRevision(String revision, String filePath) throws EVCSFileNotFound {
		final String[] branchNames = new String[1];
		ISVNLogEntryHandler handler = new ISVNLogEntryHandler() {
            @Override
            public void handleLogEntry(SVNLogEntry arg0) throws SVNException {
                Map<String, SVNLogEntryPath> map = arg0.getChangedPaths();
                for (Map.Entry<String, SVNLogEntryPath> entry: map.entrySet()) {
                	if (branchNames[0] == null) {
	                    SVNLogEntryPath svnLogEntryPath = entry.getValue();
	                    branchNames[0] = svnLogEntryPath.getPath().replace(filePath, "").replaceFirst("/", "");
                	}
                }   
            }
        }; 
		
        try {
        	repository.log(new String[] {""}, Long.parseLong(revision),  Long.parseLong(revision), true, true, 1, handler);
        	return getFileContent(branchNames[0].equals(MASTER_PATH) ? null : branchNames[0], filePath, revision);
        } catch (SVNException e) {
			throw new EVCSException(e);
			
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String getFileContent(String branchName, String filePath, String revision) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			repository.getFile(new File(getBranchName(branchName), filePath).getPath().replace("\\", "/"),
					(revision == null || revision.isEmpty()) ? -1 : Long.parseLong(revision), new SVNProperties(), baos);
			return baos.toString(StandardCharsets.UTF_8.name());
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_FILE_NOT_FOUND_ERROR_CODE) {
				try {
					if (repository.checkPath(getBranchName(branchName), -1L) == SVNNodeKind.NONE) {
						throw new EVCSBranchNotFound(getRepoUrl(), getBranchName(branchName));
					}
				} catch (SVNException e1) {
					throw new EVCSException(e1);
				}
				throw new EVCSFileNotFound(getRepoUrl(), filePath, revision);
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
	public VCSCommit setFileContent(String branchName, List<VCSChangeListNode> vcsChangeList) {
		if (vcsChangeList.isEmpty()) {
			return null;
		}
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			checkout(getBranchUrl(branchName), wc.getFolder(), null);
			StringBuilder commitMessageSB = new StringBuilder();
			for (VCSChangeListNode vcsChangeListNode : vcsChangeList) {
				String filePath = vcsChangeListNode.getFilePath();
				File file = new File(wc.getFolder(), filePath);
				Boolean needToAdd = !file.exists();
				if (needToAdd) {
					FileUtils.forceMkdir(file.getParentFile());
					file.createNewFile();
				}
	
				try (FileWriter writer = new FileWriter(file)) {
					writer.write(vcsChangeListNode.getContent());
				}

				if (needToAdd) {
					clientManager
							.getWCClient()
							.doAdd(file,
									true /* force, avoiding "file is already under version control" exception */,
									false, false, SVNDepth.EMPTY, false, true);
				}
				commitMessageSB.append(vcsChangeListNode.getLogMessage() + VCSChangeListNode.COMMIT_MESSAGES_SEPARATOR);
			}

			commitMessageSB.setLength(commitMessageSB.length() - VCSChangeListNode.COMMIT_MESSAGES_SEPARATOR.length());
			String commitMessage = commitMessageSB.toString();
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
	public VCSCommit setFileContent(String branchName, String filePath, String content, String commitMessage) {
		return setFileContent(branchName, Collections.singletonList(new VCSChangeListNode(filePath, content, commitMessage)));
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
	
	private SVNLogEntry getDirFirstCommit(final String dir) throws SVNException {
		@SuppressWarnings("unchecked")
		Collection<SVNLogEntry> entries = repository.log(new String[] { dir }, null, 0 /* start from first commit */,
				-1 /* to the head commit */, true, true);
		return entries.iterator().next();
	}

	SVNLogEntry getBranchFirstCommit(final String branchPath) throws SVNException {
		return getDirFirstCommit(getBranchName(branchPath));
	}
	
	private List<VCSDiffEntry> getDiffEntries(final String srcBranchName, final String dstBranchName)
			throws Exception {
		final SvnOperationFactory svnOperationFactory = new SvnOperationFactory();
		final SvnDiffSummarize summarizeDiff = svnOperationFactory.createDiffSummarize();
		final List<VCSDiffEntry> res = new ArrayList<>();
		
		summarizeDiff.setSources(
				SvnTarget.fromURL(getBranchUrl(dstBranchName), SVNRevision.HEAD),
				SvnTarget.fromURL(getBranchUrl(srcBranchName), SVNRevision.HEAD));
		
		summarizeDiff.setReceiver((target, diffStatus) -> {
			if (diffStatus.getPath().length() == 0) {
				return;
			}
			VCSDiffEntry entry = new VCSDiffEntry(diffStatus.getPath(),
					SVNChangeTypeToVCSChangeType(diffStatus.getModificationType()), null);
			res.add(entry);
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
			checkout(getBranchUrl(dstBranchName), wc.getFolder(), null);
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
			List<String> entries = listEntries(SVNVCS.BRANCHES_PATH + (path == null ? "" : path));
			Set<String> tempRes = new HashSet<>(entries);
			if (repository.checkPath(MASTER_PATH, -1) == SVNNodeKind.DIR) {
				if (path == null || MASTER_PATH.startsWith(path) ) {
					tempRes.add(MASTER_PATH.replace("/", ""));
				}
			}
			Set<String> res = new HashSet<>();
			for (String str : tempRes) {
				res.add(StringUtils.removeStart(str, SVNVCS.BRANCHES_PATH));
			}
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	List<String> listEntries(String path) throws SVNException {
		List<String> res = new ArrayList<>();
		if (path == null) {
			return res;
		}
		path = path.trim();
		String lastFolder;
		String folderPrefix;
		int lastSlashIndex = path.lastIndexOf("/");
		lastFolder = lastSlashIndex > 0 ? path.substring(0, lastSlashIndex) : path;
		folderPrefix =  lastSlashIndex > 0 ? path.substring(lastSlashIndex + 1) : "";
		
		Collection<SVNDirEntry> entries = repository.getDir(lastFolder, -1 , null , (Collection<SVNDirEntry>) null);
		List<SVNDirEntry> entriesList = new ArrayList<>(entries);
		Collections.sort(entriesList, (o1, o2) -> {
			if (o1.getRevision() < o2.getRevision()) {
				return -1;
			}
			if (o1.getRevision() > o2.getRevision()) {
				return 1;
			}
			return 0;
		});
		for (SVNDirEntry entry : entriesList) {
			if (entry.getKind() == SVNNodeKind.DIR && entry.getName().startsWith(folderPrefix)) {
				String branchName = (path.isEmpty() ? "" : StringUtils.appendIfMissing(lastFolder, "/")) + entry.getName();
				res.add(branchName);
			}
		}
		
		return res;
	}
	
	@Override
	public List<VCSCommit> log(String branchName, int limit) {
		final List<VCSCommit> res = new ArrayList<>();
		try {
			getBranchUrl(branchName); // for exception test only
			repository.log(new String[] { getBranchName(branchName) }, 
					-1L /* start from head descending */, 
					0L, true, true, limit, logEntry -> res.add(svnLogEntryToVCSCommit(logEntry)));
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}

	@Override
	public String getVCSTypeString() {
		return SVN_VCS_TYPE_STRING;
	}

	@Override
	public VCSCommit removeFile(String branchName, String filePath, String commitMessage) {
		try {
			SVNCommitInfo res = clientManager
					.getCommitClient()
					.doDelete(new SVNURL[] {getBranchUrl(branchName).appendPath(filePath, true)}, commitMessage);
			return new VCSCommit(Long.toString(res.getNewRevision()), commitMessage, res.getAuthor());
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}
	
	@Override
	public List<VCSCommit> getCommitsRange(String branchName, String startRevision, WalkDirection direction, int limit) {
		final List<VCSCommit> res = new ArrayList<>();
		try {
			Long startRevisionLong;
			Long endRevisionLong;
			if (direction == WalkDirection.ASC) {
				startRevisionLong = startRevision == null ? getBranchFirstCommit(branchName).getRevision() :
					Long.parseLong(startRevision);
				endRevisionLong = Long.parseLong(getHeadCommit(branchName).getRevision());
			} else {
				startRevisionLong = startRevision == null ? Long.parseLong(getHeadCommit(branchName).getRevision()) :
					Long.parseLong(startRevision);
				endRevisionLong = getBranchFirstCommit(branchName).getRevision();
			}
			repository.log(new String[] { getBranchName(branchName) }, startRevisionLong, endRevisionLong, true, true, limit,
					logEntry -> {
						VCSCommit commit = svnLogEntryToVCSCommit(logEntry);
						res.add(commit);
					});
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}
	
	private VCSCommit svnLogEntryToVCSCommit(SVNLogEntry logEntry) {
		return new VCSCommit(Long.toString(logEntry.getRevision()), logEntry.getMessage(),
				logEntry.getAuthor());
	}

	@Override
	public List<VCSCommit> getCommitsRange(String branchName, String startRevision, String endRevision) {
		final List<VCSCommit> res = new ArrayList<>();
		try {
			Long startRevisionLong = startRevision == null ?
					getBranchFirstCommit(branchName).getRevision() :
					Long.parseLong(startRevision);
			Long endRevisionLong = endRevision == null ? -1L : Long.parseLong(endRevision);
			repository.log(new String[] { getBranchName(branchName) }, startRevisionLong, endRevisionLong, true, true, 0 /* limit */,
					logEntry -> res.add(svnLogEntryToVCSCommit(logEntry)));
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}

	@Override
	public VCSCommit getHeadCommit(String branchName) {
		try {
			SVNLogEntry headEntry = getDirHeadLogEntry(getBranchName(branchName));
			return new VCSCommit(Long.toString(headEntry.getRevision()), headEntry.getMessage(), headEntry.getAuthor());
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_FILE_NOT_FOUND_ERROR_CODE) {
				return null;
			}
			throw new EVCSException(e);
		}
	}
	
	SVNLogEntry getDirHeadLogEntry(String dir) throws SVNException {
		@SuppressWarnings("unchecked")
		Collection<SVNLogEntry> entries = repository.log(new String[] { dir }, null, -1 /* start from head commit */,
				0 /* to the first commit */, true, true);
		return entries.iterator().next();
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
		}
	}
	
	@Override
	public VCSTag createTag(String branchName, String tagName, String tagMessage, String revisionToTag) throws EVCSTagExists {
		try {
			SVNURL srcURL = getBranchUrl(branchName);
			SVNURL dstURL = SVNURL.parseURIEncoded(repoUrl + TAGS_PATH + tagName);
			SVNLogEntry copyFromEntry = revToSVNEntry(getBranchName(branchName),
					revisionToTag == null ? SVNRevision.HEAD.getNumber() : Long.parseLong(revisionToTag));
			SVNCopySource copySource = revisionToTag == null ?
					new SVNCopySource(SVNRevision.HEAD, SVNRevision.create(copyFromEntry.getRevision()), srcURL) :
					new SVNCopySource(SVNRevision.parse(revisionToTag), SVNRevision.parse(revisionToTag), srcURL);

			clientManager.getCopyClient().doCopy(new SVNCopySource[] {copySource}, dstURL, 
			        false, false, true, tagMessage, null);

			SVNDirEntry entry = repository.info(TAGS_PATH + tagName, -1);

			return new VCSTag(tagName, tagMessage, entry.getAuthor(), svnLogEntryToVCSCommit(copyFromEntry));
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_ITEM_EXISTS_ERROR_CODE) {
				throw new EVCSTagExists(e);
			} 
			throw new EVCSException(e);
		}
	}
	
	SVNLogEntry revToSVNEntry(String branchName, Long rev) throws SVNException {
		SVNDirEntry info = repository.info(branchName, rev);
		@SuppressWarnings("unchecked")
		Collection<SVNLogEntry> entries = repository.log(new String[] {branchName}, null, info.getRevision(), info.getRevision(), true, true);
		if (entries != null) {
			return entries.iterator().next();
		}
		return null;
	}
	
	@Override
	public List<VCSTag> getTags() {
		try {
			return getTags(null);
		} catch (SVNException e) {
			throw new EVCSException(e);
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
		}
	}

	@Override
	public void checkout(String branchName, String targetPath, String revision) {
		try {
			checkout(getBranchUrl(branchName), new File(targetPath), revision);
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}
	
	List<VCSTag> getTags(String onRevision) throws SVNException {
		List<VCSTag> res = new ArrayList<>();
		@SuppressWarnings("unchecked")
		Collection<SVNDirEntry> dirEntries = repository.getDir(TAGS_PATH, -1 , null, (Collection<SVNDirEntry>) null);
		for (SVNDirEntry dirEntry : dirEntries) {
			long tagCopyFrom = 0;
			
			SVNLogEntry tagEntry = getDirFirstCommit(TAGS_PATH + dirEntry.getName());
			for (SVNLogEntryPath entryPath : tagEntry.getChangedPaths().values()) {
				tagCopyFrom = entryPath.getCopyRevision();
			}
			
			if (onRevision == null || tagCopyFrom == Long.parseLong(onRevision)) {
				SVNProperties props = repository.getRevisionProperties(tagCopyFrom, null);
				res.add(new VCSTag(dirEntry.getName(), tagEntry.getMessage(), tagEntry.getAuthor(), new VCSCommit(Long.toString(tagCopyFrom),
						props.getStringValue(SVNRevisionProperty.LOG), props.getStringValue(SVNRevisionProperty.AUTHOR))));
			}
		}
		return res;
	}

	@Override
	public List<VCSTag> getTagsOnRevision(String revision) {
		try {
			return getTags(revision);
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_FILE_NOT_FOUND_ERROR_CODE) {
				return new ArrayList<>();
			}
			throw new EVCSException(e);
		}
	}
}
