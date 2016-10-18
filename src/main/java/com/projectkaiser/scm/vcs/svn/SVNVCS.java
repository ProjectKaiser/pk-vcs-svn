package com.projectkaiser.scm.vcs.svn;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.tmatesoft.svn.core.ISVNLogEntryHandler;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.auth.SVNPasswordAuthentication;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.internal.wc.SVNFileType;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.ISVNConflictHandler;
import org.tmatesoft.svn.core.wc.ISVNOptions;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNConflictChoice;
import org.tmatesoft.svn.core.wc.SVNConflictDescription;
import org.tmatesoft.svn.core.wc.SVNConflictResult;
import org.tmatesoft.svn.core.wc.SVNCopyClient;
import org.tmatesoft.svn.core.wc.SVNCopySource;
import org.tmatesoft.svn.core.wc.SVNDiffClient;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNRevisionRange;
import org.tmatesoft.svn.core.wc.SVNStatusType;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;
import org.tmatesoft.svn.core.wc.SVNWCClient;
import org.tmatesoft.svn.core.wc.SVNWCUtil;
import org.tmatesoft.svn.core.wc2.ISvnObjectReceiver;
import org.tmatesoft.svn.core.wc2.SvnDiffStatus;
import org.tmatesoft.svn.core.wc2.SvnDiffSummarize;
import org.tmatesoft.svn.core.wc2.SvnOperationFactory;
import org.tmatesoft.svn.core.wc2.SvnTarget;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.VCSChangeType;
import com.projectkaiser.scm.vcs.api.VCSDiffEntry;
import com.projectkaiser.scm.vcs.api.VCSMergeResult;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSBranchExists;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSException;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSFileNotFound;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSLockedWorkingCopy;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSRepositoryWorkspace;

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
	private IVCSRepositoryWorkspace repo;
	private String repoUrl;
	
	public static final String MASTER_PATH= "trunk/";
	public static final String BRANCHES_PATH = "branches/";
	private static final String SVN_VCS_TYPE_STRING = "svn";
	
	public SVNClientManager getClientManager() {
		return clientManager;
	}
	
	public ISVNOptions getOptions() {
		return options;
	}
	
	public SVNVCS(IVCSRepositoryWorkspace repo, String user, String password) {
		this.repo = repo;
		repoUrl = repo.getRepoUrl().trim();
		if (!repoUrl.endsWith("/") && !repoUrl.endsWith("\\")) {
			repoUrl += "/";
		}
		DAVRepositoryFactory.setup(); 
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
		
		SVNFileType.setSymlinkSupportEnabled(false);
	}
	
	public SVNRepository getRepository() {
		return repository;
	}
	
	private SVNURL getBranchUrl(String branchPath) throws SVNException {
		return SVNURL.parseURIEncoded(repoUrl + (branchPath == null ? MASTER_PATH : BRANCHES_PATH + branchPath));
	}
	
	@Override
	public void createBranch(String srcBranchPath, String dstBranchPath, String commitMessage) {
		try {
			SVNURL fromUrl = getBranchUrl(srcBranchPath);
			SVNURL toUrl = getBranchUrl(dstBranchPath);
			createBranch(fromUrl, toUrl, commitMessage);
		} catch (SVNException e) {
			throw new EVCSException(e);
		} 
	}
	
	public void createBranch(SVNURL fromUrl, SVNURL toUrl, String commitMessage) {
		try {
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
			}
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
	public void deleteBranch(String branchPath, String commitMessage) {
		try {
			clientManager
					.getCommitClient()
					.doDelete(new SVNURL[] { getBranchUrl(branchPath) }, commitMessage);
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}
	
	@Override
	public VCSMergeResult merge(String srcBranchPath, String dstBranchPath, String commitMessage) {
		SVNDiffClient diffClient = clientManager.getDiffClient();
		try {
			try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
				checkout(getBranchUrl(dstBranchPath), wc.getFolder());
				
				DefaultSVNOptions options = (DefaultSVNOptions) diffClient.getOptions();
				final VCSMergeResult res = new VCSMergeResult();
				options.setConflictHandler(new ISVNConflictHandler() {
					@Override
					public SVNConflictResult handleConflict(SVNConflictDescription conflictDescription)
							throws SVNException {
						res.getConflictingFiles().add(conflictDescription.getMergeFiles().getLocalPath());
						return new SVNConflictResult(SVNConflictChoice.POSTPONE, 
								conflictDescription.getMergeFiles().getResultFile()); 
					}
				});

				try {
					SVNRevisionRange range = new SVNRevisionRange(SVNRevision.create(1), SVNRevision.HEAD);
					diffClient.doMerge(getBranchUrl(srcBranchPath),
							SVNRevision.HEAD, Collections.singleton(range),
							wc.getFolder(), SVNDepth.UNKNOWN, true, false, false, false);
					
					res.setSuccess(res.getConflictingFiles().isEmpty());
					if (res.getSuccess()) {
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
				} catch (Exception e) {
					wc.setCorrupted(true);
					throw e;
				}
				
				return res;
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

	public void checkout(SVNURL sourceUrl, File destPath) throws SVNException {
		SVNUpdateClient updateClient = clientManager.getUpdateClient();
		updateClient.setIgnoreExternals(false);
		if (isWorkingCopyInited(destPath)) {
			updateClient.doSwitch(destPath, sourceUrl, SVNRevision.HEAD, 
					SVNRevision.HEAD, SVNDepth.INFINITY, false, false);
		} else {
			updateClient.doCheckout(sourceUrl, destPath, SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.INFINITY, true);
		}
	}

	private boolean isWorkingCopyInited(File destPath) {
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
		userPassAuth = SVNPasswordAuthentication.newInstance(user, password.toCharArray(), true, trunkSVNUrl, false);
		authManager.setAuthentications(new SVNAuthentication[] {userPassAuth});
	}

	@Override
	public void setProxy(String host, int port, String proxyUser, String proxyPassword) {
		authManager.setProxy(host, port, proxyUser, proxyPassword.toCharArray());
	}


	@Override
	public String getFileContent(String branchName, String filePath, String encoding) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream( );
		try {
			repository.getFile(new File((branchName == null ? MASTER_PATH : BRANCHES_PATH + branchName),
					filePath).getPath().replace("\\", "/"), -1, new SVNProperties(), baos);
			return baos.toString(encoding);
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_FILE_NOT_FOUND_ERROR_CODE) {
				throw new EVCSFileNotFound(e);
			}
			throw new EVCSException(e);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		
	}

	@Override
	public String getFileContent(String branchName, String filePath) {
		return getFileContent(branchName, filePath, StandardCharsets.UTF_8.name());
	}

	@Override
	public void setFileContent(String branchName, String filePath, String content, String commitMessage) {
		try {
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
										true /* force, avoiding "file is already under version control" exception*/,
										false, false, SVNDepth.EMPTY, false, true);
					}
					
					clientManager
							.getCommitClient()
							.doCommit(new File[] { wc.getFolder() }, false, commitMessage,
									new SVNProperties(), null, false, false, SVNDepth.INFINITY);
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

	@Override
	public List<VCSDiffEntry> getBranchesDiff(final String srcBranchName, final String destBranchName) {
		try {
			SvnOperationFactory svnOperationFactory = new SvnOperationFactory();
			SvnDiffSummarize diff = svnOperationFactory.createDiffSummarize();
			diff.setSources(
					SvnTarget.fromURL(getBranchUrl(destBranchName), SVNRevision.HEAD),
					SvnTarget.fromURL(getBranchUrl(srcBranchName), SVNRevision.HEAD));
			final List<VCSDiffEntry> res = new ArrayList<>();
			diff.setReceiver(new ISvnObjectReceiver<SvnDiffStatus>() {
	            public void receive(SvnTarget target, SvnDiffStatus diffStatus) throws SVNException {
	                res.add(new VCSDiffEntry(diffStatus.getPath(),
	                		SVNChangeTypeToVCSChangeType(diffStatus.getModificationType())));
	            }

				private VCSChangeType SVNChangeTypeToVCSChangeType(SVNStatusType modificationType) {
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
	        });
			diff.run();
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public void createTrunk(String commitMessage) throws SVNException {
		createBranch(SVNURL.parseURIEncoded(repoUrl), SVNURL.parseURIEncoded(repoUrl + MASTER_PATH), commitMessage);
	}
	
	public void createBranches(String commitMessage) throws SVNException {
		createBranch(SVNURL.parseURIEncoded(repoUrl), SVNURL.parseURIEncoded(repoUrl + BRANCHES_PATH), commitMessage);
	}

	@Override
	public Set<String> getBranches() {
		try {
			Set<String> res = new HashSet<>();
			listEntries(res, SVNVCS.BRANCHES_PATH);
			addTrunkIfExists(res);
			return res;
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private void addTrunkIfExists(Set<String> res) {
		try {
			if (repository.checkPath(MASTER_PATH, -1) == SVNNodeKind.DIR) {
				res.add(MASTER_PATH.replace("/", ""));
			}
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
		
	}

	private void listEntries(Set<String> entries, String path) throws SVNException {
		@SuppressWarnings("unchecked")
		Collection<SVNDirEntry> subEntries = repository.getDir(path, -1, null, (Collection<SVNDirEntry>) null);
		for (SVNDirEntry entry : subEntries) {
			if (entry.getKind() == SVNNodeKind.DIR) {
				entries.add(((path.equals(SVNVCS.BRANCHES_PATH) ? "" : path + "/") + entry.getName())
						.replace(SVNVCS.BRANCHES_PATH, ""));
			}
		}
	}

	@Override
	public List<String> getCommitMessages(String branchName, Integer limit) {
		final List<String> res = new ArrayList<>();
		try {
			repository.log(new String[] { branchName }, -1 /* start from head descending */, 
					0, true, true, limit, new ISVNLogEntryHandler() {
				@Override
				public void handleLogEntry(SVNLogEntry logEntry) throws SVNException {
					res.add(logEntry.getMessage());
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
	public void removeFile(String branchName, String filePath, String commitMessage) {
		try {
			try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
				clientManager
						.getCommitClient()
						.doDelete(new SVNURL[] {getBranchUrl(branchName).appendPath(filePath, true)}, commitMessage);
			}
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
	}
}
