package com.projectkaiser.scm.vcs.svn;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.apache.commons.logging.Log;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
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
import org.tmatesoft.svn.core.wc.SVNCommitClient;
import org.tmatesoft.svn.core.wc.SVNConflictChoice;
import org.tmatesoft.svn.core.wc.SVNConflictDescription;
import org.tmatesoft.svn.core.wc.SVNConflictResult;
import org.tmatesoft.svn.core.wc.SVNCopyClient;
import org.tmatesoft.svn.core.wc.SVNCopySource;
import org.tmatesoft.svn.core.wc.SVNDiffClient;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNRevisionRange;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;
import org.tmatesoft.svn.core.wc.SVNWCClient;
import org.tmatesoft.svn.core.wc.SVNWCUtil;

import com.projectkaiser.scm.vcs.api.AbstractVCS;
import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.PKVCSMergeResult;
import com.projectkaiser.scm.vcs.api.VCSWorkspace;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSBranchExists;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSException;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSFileNotFound;

public class SVNVCS extends AbstractVCS implements IVCS {
	
	private static final int SVN_PATH_IS_NOT_WORKING_COPY_ERROR_CODE = 155007;
	private static final int SVN_ITEM_EXISTS_ERROR_CODE = 160020;
	private static final int SVN_FILE_NOT_FOUND_ERROR_CODE = 160013;
	private BasicAuthenticationManager authManager;
	private SVNRepository repository;
	private ISVNOptions options;
	private SVNClientManager clientManager;
	private SVNURL trunkSVNUrl;
	private SVNAuthentication userPassAuth;
	
	public SVNClientManager getClientManager() {
		return clientManager;
	}
	
	public SVNVCS(Log logger, String workspaceBasePath, String trunkUrl, String user, String password) {
		super(logger, workspaceBasePath, trunkUrl);
		trunkUrl = trunkUrl.trim();
		if (!trunkUrl.endsWith("\\") && !trunkUrl.endsWith("/")) {
			trunkUrl += "/";
		}
		
		DAVRepositoryFactory.setup(); 
        options = SVNWCUtil.createDefaultOptions(true); 
		try {
			trunkSVNUrl = SVNURL.parseURIEncoded(trunkUrl);
			repository = SVNRepositoryFactory.create(trunkSVNUrl);
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
		
		userPassAuth = SVNPasswordAuthentication.newInstance(user, password.toCharArray(), true, trunkSVNUrl, false);
		authManager = new BasicAuthenticationManager(new SVNAuthentication[] {userPassAuth});
		repository.setAuthenticationManager(authManager);
		
		clientManager = SVNClientManager.newInstance(
				options, repository.getAuthenticationManager());
		
		SVNFileType.setSymlinkSupportEnabled(false);
	}
	
	public SVNRepository getRepository() {
		return repository;
	}

	
	@Override
	public void createBranch(String srcBranchPath, String dstBranchPath, String commitMessage) {
		SVNURL urlObj;
		SVNURL toUrl;
		try {
			urlObj = SVNURL.parseURIEncoded(baseUrl + srcBranchPath);
			toUrl = SVNURL.parseURIEncoded(baseUrl+ dstBranchPath);
		
			SVNCopyClient copyClient = new SVNCopyClient(authManager, options);
			SVNCopySource copySource = new SVNCopySource(SVNRevision.UNDEFINED, SVNRevision.HEAD,
					urlObj);
	
			copyClient.doCopy(new SVNCopySource[] { copySource }, toUrl, 
					false, // isMove
					true, // make parents
					true, // failWhenDstExists
					commitMessage, // commit message
					null);
		} catch (SVNException e) {
			if (e.getErrorMessage().getErrorCode().getCode() == SVN_ITEM_EXISTS_ERROR_CODE) {
				throw new EVCSBranchExists(e);
			} 
			throw new EVCSException (e);
		}
	}

	@Override
	public void deleteBranch(String branchPath, String commitMessage) {
		SVNCommitClient client = new SVNCommitClient(authManager, options);
		try {
			client.doDelete(new SVNURL[] {SVNURL.parseURIEncoded(baseUrl + branchPath)}, commitMessage);
		} catch (SVNException e) {
			throw new EVCSException(e);
		}
	}

	@Override
	public PKVCSMergeResult merge(String srcBranchPath, String dstBranchPath, String commitMessage) {
		SVNDiffClient diffClient = clientManager.getDiffClient();
		SVNCommitClient client = new SVNCommitClient(authManager, options);
		try {
			VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(repoFolder);
			try {
				checkout(baseUrl + dstBranchPath, workspace.getFolder());
				
				DefaultSVNOptions options = (DefaultSVNOptions) diffClient.getOptions();
				final PKVCSMergeResult res = new PKVCSMergeResult();
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
					diffClient.doMerge(SVNURL.parseURIEncoded(baseUrl + srcBranchPath),
							SVNRevision.HEAD, Collections.singleton(range),
							workspace.getFolder(), SVNDepth.UNKNOWN, true, false, false, false);
					
//					diffClient.doMergeReIntegrate(SVNURL.parseURIEncoded(baseUrl + srcBranchPath), SVNRevision.HEAD, 
//							workspace.getFolder(), false);
//					
					res.setSuccess(res.getConflictingFiles().isEmpty());
					if (res.getSuccess()) {
						client.doCommit(new File[] {workspace.getFolder()}, false, commitMessage, 
								new SVNProperties(), null, true, true, SVNDepth.INFINITY);
					} else {
						try {
							SVNWCClient wcClient = new SVNWCClient(authManager, options);
							wcClient.doRevert(new File[] {workspace.getFolder()}, SVNDepth.INFINITY, null);
						} catch (Exception e) {
							// It doesn't matter if we failed to revert. Just make the workspace corrupted.
							workspace.setCorrupt(true);
						}
					}
				} catch (Exception e) {
					workspace.setCorrupt(true);
					throw e;
				}
				
				return res;
			} finally {
				workspace.unlock();
			} 
		} catch (SVNException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public void checkout(String sourceUrl, File destPath) throws SVNException {
		SVNUpdateClient updateClient = clientManager.getUpdateClient();
		updateClient.setIgnoreExternals(false);
		SVNURL url =SVNURL.parseURIEncoded(sourceUrl);
		if (isWorkingCopyInited(destPath)) {
			updateClient.doSwitch(destPath, url, SVNRevision.HEAD, 
					SVNRevision.HEAD, SVNDepth.INFINITY, false, false);
		} else {
			updateClient.doCheckout(url, destPath, SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.INFINITY, true);
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
			repository.getFile(new File(branchName, filePath).getPath().replace("\\", "/"), -1, new SVNProperties(), baos);
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
		VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(repoFolder);
		try {
			try {
				checkout(baseUrl + branchName, workspace.getFolder());
				File file = new File(workspace.getFolder(), filePath);
				FileWriter writer = new FileWriter(file);
				writer.write(content);
				writer.close();
				
				clientManager
						.getCommitClient()
						.doCommit(new File[] { workspace.getFolder() }, false, commitMessage,
								new SVNProperties(), null, false, false, SVNDepth.INFINITY);
				
			} catch (SVNException e) {
				throw new EVCSException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} finally {
			workspace.unlock();
		}
	}
}
