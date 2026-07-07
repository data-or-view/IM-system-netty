package com.im.bootstrap;

import com.im.api.IAuthenticator;
import com.im.api.IConversationManager;
import com.im.api.IFriendManager;
import com.im.api.IGroupManager;
import com.im.api.IPasswordHasher;
import com.im.api.IUserCredentialStore;
import com.im.api.IUserManager;
import com.im.common.retry.RetryExecutor;

record BusinessDependencies(IAuthenticator authenticator,
                            RetryExecutor retryExecutor,
                            IGroupManager groupManager,
                            IConversationManager conversationManager,
                            IFriendManager friendManager,
                            IUserManager userManager,
                            IUserCredentialStore credentialStore,
                            IPasswordHasher passwordHasher) {
}
