package com.im.bootstrap;

import com.im.api.IAuthenticator;
import com.im.api.IFileStorageService;
import com.im.api.Operation;
import com.im.config.Config;
import com.im.core.access.ConversationAccessChecker;
import com.im.core.access.DefaultChatSendPolicy;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.handler.WebhookService;
import com.im.core.handler.unified.AuthInterceptor;
import com.im.core.handler.unified.ChatHandler;
import com.im.core.handler.unified.ConversationHandler;
import com.im.core.handler.unified.FileMultipartHandler;
import com.im.core.handler.unified.FileUploadHandler;
import com.im.core.handler.unified.FriendHandler;
import com.im.core.handler.unified.GroupHandler;
import com.im.core.handler.unified.HeartbeatHandler;
import com.im.core.handler.unified.LoginHandler;
import com.im.core.handler.unified.MessageHandler;
import com.im.core.handler.unified.RegisterHandler;
import com.im.core.handler.unified.RevokeHandler;
import com.im.core.handler.unified.TelemetryInterceptor;
import com.im.core.handler.unified.UserHandler;
import com.im.core.usecase.HeartbeatUseCase;
import com.im.core.usecase.LoginUseCase;
import com.im.core.usecase.RegisterUseCase;
import com.im.core.usecase.RevokeUseCase;
import com.im.core.usecase.SendMessageUseCase;
import com.im.core.webhook.LocalWebhookManager;
import com.im.infrastructure.storage.usecase.FileUploadUseCase;
import com.im.infrastructure.storage.usecase.MultipartUploadUseCase;

/**
 * Builds the protocol dispatcher and its handler graph.
 *
 * <p>This lives outside {@link IMServer} because operation registration changes much
 * more often than the Netty/Redis/MySQL lifecycle. Keeping it isolated lets us add
 * API surface without turning the server bootstrap into the place where every
 * business dependency must be understood.</p>
 */
final class DispatcherFactory {

    private DispatcherFactory() {
    }

    static ApiDispatcher create(Config config, DispatcherDependencies dependencies) {
        LoginUseCase loginUseCase = new LoginUseCase(
                dependencies.business().authenticator(), dependencies.storage().messageStore(),
                dependencies.business().credentialStore(), dependencies.business().passwordHasher());
        RegisterUseCase registerUseCase = new RegisterUseCase(
                dependencies.business().userManager(),
                dependencies.business().credentialStore(),
                dependencies.business().passwordHasher());

        WebhookService webhookService = new WebhookService(new LocalWebhookManager(
                config.getString("im.webhook.url").orElse("")));
        DefaultChatSendPolicy chatSendPolicy = new DefaultChatSendPolicy(
                dependencies.business().userManager(),
                dependencies.business().friendManager(),
                dependencies.business().groupManager(),
                config.getBoolean("im.chat.single.require-friend", false));
        SendMessageUseCase sendMessageUseCase = new SendMessageUseCase(
                dependencies.storage().messageQueue(),
                dependencies.storage().sequenceManager(),
                webhookService,
                chatSendPolicy);
        RevokeUseCase revokeUseCase = new RevokeUseCase(
                dependencies.storage().messageStore(), dependencies.business().groupManager());
        ConversationAccessChecker conversationAccessChecker = new ConversationAccessChecker(
                dependencies.business().conversationManager(), dependencies.business().groupManager());

        ApiDispatcher dispatcher = new ApiDispatcher();
        registerInterceptors(dispatcher, dependencies.business().authenticator());
        registerBusinessHandlers(dispatcher, dependencies, loginUseCase, registerUseCase, conversationAccessChecker);
        registerMessagingHandlers(dispatcher, dependencies, sendMessageUseCase, revokeUseCase, conversationAccessChecker);
        registerFileHandlers(dispatcher, config, dependencies.storage().fileStorage());
        return dispatcher;
    }

    private static void registerInterceptors(ApiDispatcher dispatcher, IAuthenticator authenticator) {
        dispatcher.addInterceptor(new TelemetryInterceptor());
        dispatcher.addInterceptor(new AuthInterceptor(authenticator));
    }

    private static void registerBusinessHandlers(ApiDispatcher dispatcher,
                                                 DispatcherDependencies dependencies,
                                                 LoginUseCase loginUseCase,
                                                 RegisterUseCase registerUseCase,
                                                 ConversationAccessChecker conversationAccessChecker) {
        dispatcher.registerHandlers(new UserHandler(dependencies.business().userManager(), registerUseCase),
                Operation.USER_REGISTER, Operation.USER_INFO, Operation.USER_SEARCH, Operation.USER_UPDATE);
        dispatcher.registerHandlers(new FriendHandler(
                        dependencies.business().friendManager(),
                        dependencies.runtime().friendApplyNotifier()),
                Operation.FRIEND_APPLY, Operation.FRIEND_APPROVE, Operation.FRIEND_REMOVE, Operation.FRIEND_LIST,
                Operation.FRIEND_BLACK, Operation.FRIEND_UNBLACK, Operation.FRIEND_BLACKLIST,
                Operation.FRIEND_APPLY_RECEIVED, Operation.FRIEND_APPLY_SENT,
                Operation.FRIEND_APPLY_DETAIL, Operation.FRIEND_APPLY_UNHANDLED_COUNT);
        dispatcher.registerHandlers(new GroupHandler(dependencies.business().groupManager()),
                Operation.GROUP_CREATE, Operation.GROUP_JOIN, Operation.GROUP_QUIT, Operation.GROUP_KICK,
                Operation.GROUP_DISBAND, Operation.GROUP_INFO_UPDATE, Operation.GROUP_INFO,
                Operation.GROUP_LIST, Operation.GROUP_SEARCH, Operation.GROUP_MEMBERS, Operation.GROUP_MUTE_ALL,
                Operation.GROUP_APPLY_LIST, Operation.GROUP_APPLY_UNHANDLED_COUNT, Operation.GROUP_APPLY_APPROVE);
        dispatcher.registerHandlers(new ConversationHandler(
                        dependencies.business().conversationManager(), conversationAccessChecker),
                Operation.CONVERSATION_LIST, Operation.CONVERSATION_SET, Operation.CONVERSATION_READ);
        dispatcher.registerHandler(Operation.LOGIN,
                new LoginHandler(loginUseCase, dependencies.runtime().sessionManager(), dependencies.cluster().routeTable(),
                        dependencies.nodeId(), dependencies.cluster().clusterMessageBus(),
                        dependencies.runtime().sessionManager().getLoginStrategy()));
        dispatcher.registerHandler(Operation.REGISTER, new RegisterHandler(registerUseCase));
        dispatcher.registerHandler(Operation.HEARTBEAT,
                new HeartbeatHandler(new HeartbeatUseCase(dependencies.cluster().routeTable()),
                        dependencies.runtime().sessionManager(), dependencies.business().authenticator(),
                        dependencies.cluster().routeTable(), dependencies.nodeId()));
    }

    private static void registerMessagingHandlers(ApiDispatcher dispatcher,
                                                  DispatcherDependencies dependencies,
                                                  SendMessageUseCase sendMessageUseCase,
                                                  RevokeUseCase revokeUseCase,
                                                  ConversationAccessChecker conversationAccessChecker) {
        dispatcher.registerHandlers(new MessageHandler(
                        dependencies.storage().messageStore(),
                        dependencies.storage().sequenceManager(),
                        conversationAccessChecker),
                Operation.CHAT_PULL, Operation.CHAT_SEQ, Operation.CHAT_SYNC, Operation.CHAT_SEARCH);

        ChatHandler chatHandler = new ChatHandler(
                sendMessageUseCase, dependencies.call().callManager(), dependencies.call().callStateManager());
        dispatcher.registerHandler(Operation.CHAT_SEND, chatHandler);
        dispatcher.registerHandler(Operation.CHAT_SEND_GROUP, chatHandler);
        dispatcher.registerHandler(Operation.CHAT_REVOKE,
                new RevokeHandler(revokeUseCase, dependencies.runtime().sessionManager()));
    }

    private static void registerFileHandlers(ApiDispatcher dispatcher, Config config, IFileStorageService fileStorage) {
        dispatcher.registerHandler(Operation.FILE_UPLOAD,
                new FileUploadHandler(new FileUploadUseCase(
                        fileStorage, config.getLong("im.minio.max-file-size", 100L * 1024 * 1024))));
        dispatcher.registerHandlers(new FileMultipartHandler(new MultipartUploadUseCase(fileStorage)),
                Operation.FILE_MULTIPART_INIT, Operation.FILE_MULTIPART_UPLOAD,
                Operation.FILE_MULTIPART_COMPLETE, Operation.FILE_MULTIPART_ABORT);
    }
}
