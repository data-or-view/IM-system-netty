/**
 * IM测试客户端基座。
 *
 * TCP直连（端口8080），使用原生0xACAC二进制协议。
 *
 * 用法：
 *   const client = new IMClient();
 *   await client.connect('127.0.0.1', 8080);
 *   await client.login('1111', '111111');
 *   const result = await client.searchUser('2222');
 *   client.close();
 */

const net = require('net');

/**
 * 命令码 → 与 Java CommandType.java 枚举完全对齐
 *
 * 注意：LOGIN=10, REGISTER=14（不要搞混！）
 */
const CMD = {
  HEARTBEAT:         0,
  HEARTBEAT_ACK:     1,
  LOGIN:             10,
  LOGIN_ACK:         11,
  LOGOUT:            12,
  REGISTER:          14,
  REGISTER_ACK:      15,
  SINGLE_CHAT:       20,
  SINGLE_CHAT_ACK:   21,
  GROUP_CHAT:        30,
  GROUP_CHAT_ACK:    31,
  FRIEND_APPLY:      70,
  FRIEND_APPLY_ACK:  71,
  FRIEND_APPROVE:    72,
  FRIEND_APPROVE_ACK:73,
  FRIEND_REMOVE:     74,
  FRIEND_REMOVE_ACK: 75,
  FRIEND_LIST:       76,
  FRIEND_LIST_ACK:   77,
  GROUP_CREATE:      80,
  GROUP_CREATE_ACK:  81,
  GROUP_JOIN:        82,
  GROUP_JOIN_ACK:    83,
  GROUP_QUIT:        84,
  GROUP_QUIT_ACK:    85,
  USER_SEARCH:       92,
  USER_SEARCH_ACK:   93,
  GROUP_SEARCH:      94,
  GROUP_SEARCH_ACK:  95,
  FILE_UPLOAD:       100,
  FILE_UPLOAD_ACK:   101,
  FILE_DOWNLOAD:     102,
  FILE_DOWNLOAD_ACK: 103,
  ERROR:             99,
};

const MAGIC = 0xACAC;
const VERSION = 1;
const FIXED_HEADER_LENGTH = 10;

class IMError extends Error {
  constructor(code, message, detail) {
    super(message);
    this.code = code;
    this.detail = detail;
  }
}

class IMClient {
  constructor() {
    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.pendingResolve = null;
    this.pendingTimeout = null;
    this.pendingExpectedOps = null;
    this.seqCounter = 0;
    this.userId = null;
    this.token = null;
    this.onMessage = null;
    this._onDataBound = this._onData.bind(this);
  }

  /** TCP 连接 */
  connect(host = '127.0.0.1', port = 8080) {
    return new Promise((resolve, reject) => {
      this.socket = new net.Socket();
      this.socket.once('connect', () => resolve());
      this.socket.once('error', reject);
      this.socket.connect(port, host);
      this.socket.on('data', this._onDataBound);
    });
  }

  close() {
    if (this.pendingTimeout) clearTimeout(this.pendingTimeout);
    if (this.socket) {
      this.socket.removeListener('data', this._onDataBound);
      this.socket.destroy();
      this.socket = null;
    }
    this.buffer = Buffer.alloc(0);
  }

  /** 发送一帧并等待指定 op 的 ACK */
  async sendAndWait(headers, expectedOps, timeoutMs = 5000, body) {
    const seq = ++this.seqCounter;
    const expected = Array.isArray(expectedOps) ? expectedOps : [expectedOps];
    const frame = this._encodeFrame({ ...headers, _seq: String(seq), _ts: String(Date.now()) }, body);
    this.socket.write(frame);

    return this._waitForSeq(seq, expected, timeoutMs);
  }

  /** 发送一帧，不等待响应 */
  send(headers, body) {
    const seq = ++this.seqCounter;
    const frame = this._encodeFrame({ ...headers, _seq: String(seq), _ts: String(Date.now()) }, body);
    this.socket.write(frame);
    return seq;
  }

  // ========== 业务方法 ==========

  async register(userId, password) {
    const result = await this.sendAndWait(
      { _op: String(CMD.REGISTER), userId, password },
      [CMD.REGISTER_ACK, CMD.ERROR]
    );
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '注册失败', result?.reason);
    }
    this.userId = userId;
  }

  async login(userId, password) {
    const result = await this.sendAndWait(
      { _op: String(CMD.LOGIN), userId, password, _pf: '5' },
      [CMD.LOGIN_ACK, CMD.ERROR]
    );
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '登录失败', result?.reason);
    }
    this.userId = userId;
    this.token = result.token;
    return { userId, token: result.token, appManagerLevel: parseInt(result.lvl || '0') };
  }

  async searchUser(keyword, limit = 20) {
    const result = await this.sendAndWait(
      { _op: String(CMD.USER_SEARCH), keyword, limit: String(limit), userId: this.userId, Authorization: `Bearer ${this.token}` },
      [CMD.USER_SEARCH_ACK, CMD.ERROR]
    );
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '搜索用户失败', result?.reason);
    }
    return JSON.parse(result.users || '[]');
  }

  async searchGroup(keyword, limit = 20) {
    const result = await this.sendAndWait(
      { _op: String(CMD.GROUP_SEARCH), keyword, limit: String(limit), userId: this.userId, Authorization: `Bearer ${this.token}` },
      [CMD.GROUP_SEARCH_ACK, CMD.ERROR]
    );
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '搜索群组失败', result?.reason);
    }
    return JSON.parse(result.groups || '[]');
  }

  async applyFriend(toUserId, reqMsg) {
    const headers = { _op: String(CMD.FRIEND_APPLY), userId: this.userId, toUserId, Authorization: `Bearer ${this.token}` };
    if (reqMsg) headers.reqMsg = reqMsg;
    const result = await this.sendAndWait(headers, [CMD.FRIEND_APPLY_ACK, CMD.ERROR]);
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '加好友失败', result?.reason);
    }
    return result;
  }

  async approveFriend(fromUserId, agreed = true) {
    const headers = {
      _op: String(CMD.FRIEND_APPROVE), userId: this.userId, fromUserId,
      agreed: String(agreed), Authorization: `Bearer ${this.token}`
    };
    const result = await this.sendAndWait(headers, [CMD.FRIEND_APPROVE_ACK, CMD.ERROR]);
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '通过好友失败', result?.reason);
    }
    return result;
  }

  async removeFriend(friendUserId) {
    const result = await this.sendAndWait(
      { _op: String(CMD.FRIEND_REMOVE), userId: this.userId, friendUserId, Authorization: `Bearer ${this.token}` },
      [CMD.FRIEND_REMOVE_ACK, CMD.ERROR]
    );
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '删除好友失败', result?.reason);
    }
    return result;
  }

  async fetchFriendList() {
    const result = await this.sendAndWait(
      { _op: String(CMD.FRIEND_LIST), userId: this.userId, Authorization: `Bearer ${this.token}` },
      [CMD.FRIEND_LIST_ACK, CMD.ERROR]
    );
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '获取好友列表失败', result?.reason);
    }
    return JSON.parse(result.friends || '[]');
  }

  async joinGroup(groupId) {
    const result = await this.sendAndWait(
      { _op: String(CMD.GROUP_JOIN), userId: this.userId, groupId, Authorization: `Bearer ${this.token}` },
      [CMD.GROUP_JOIN_ACK, CMD.ERROR]
    );
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '加入群组失败', result?.reason);
    }
    return result;
  }

  async quitGroup(groupId) {
    const result = await this.sendAndWait(
      { _op: String(CMD.GROUP_QUIT), userId: this.userId, groupId, Authorization: `Bearer ${this.token}` },
      [CMD.GROUP_QUIT_ACK, CMD.ERROR]
    );
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '退出群组失败', result?.reason);
    }
    return result;
  }

  /** 发送单聊消息 */
  async createGroup(groupId, groupName, members = [], timeoutMs = 5000) {
    const result = await this.sendAndWait({
      _op: String(CMD.GROUP_CREATE),
      userId: this.userId,
      groupId,
      groupName,
      members: JSON.stringify(members),
      Authorization: `Bearer ${this.token}`,
    }, [CMD.GROUP_CREATE_ACK, CMD.ERROR], timeoutMs);
    if (!result || result.status !== 'OK') {
      throw new IMError(result?._err || 'UNKNOWN', '创建群失败', result?.reason);
    }
    return result;
  }

  async sendGroupMessage(groupId, contentType, content, timeoutMs = 5000) {
    const result = await this.sendAndWait({
      _op: String(CMD.GROUP_CHAT),
      fromUserId: this.userId,
      groupId,
      contentType: String(contentType),
      content,
      Authorization: `Bearer ${this.token}`,
    }, [CMD.GROUP_CHAT_ACK, CMD.ERROR, CMD.GROUP_CHAT], timeoutMs);
    if (!result || (result.status !== 'OK' && result.status !== 'RECEIVED')) {
      throw new IMError(result?._err || 'UNKNOWN', '发送群消息失败', result?.reason);
    }
    return result;
  }

  async sendMessage(toUserId, contentType, content, timeoutMs = 5000) {
    const result = await this.sendAndWait({
      _op: String(CMD.SINGLE_CHAT),
      fromUserId: this.userId,
      toUserId,
      contentType: String(contentType),
      content,
      Authorization: `Bearer ${this.token}`,
    }, [CMD.SINGLE_CHAT_ACK, CMD.ERROR, CMD.SINGLE_CHAT], timeoutMs);
    if (!result || (result.status !== 'OK' && result.status !== 'RECEIVED')) {
      throw new IMError(result?._err || 'UNKNOWN', '发送消息失败', result?.reason);
    }
    return result;
  }

  // ==================== 工具方法 ====================

  _encodeFrame(headers, body) {
    const headerJson = JSON.stringify(headers);
    const headerBytes = Buffer.from(headerJson, 'utf-8');
    const contentLen = body ? body.length : 0;
    const bodyLen = headerBytes.length + contentLen;
    const buf = Buffer.alloc(FIXED_HEADER_LENGTH + bodyLen);
    buf.writeUInt16BE(MAGIC, 0);
    buf.writeUInt8(VERSION, 2);
    buf.writeUInt8(bodyLen > 0 ? 1 : 0, 3);
    buf.writeUInt32BE(bodyLen, 4);
    buf.writeUInt16BE(headerBytes.length, 8);
    headerBytes.copy(buf, 10);
    if (body && body.length > 0) {
      if (Buffer.isBuffer(body)) {
        body.copy(buf, 10 + headerBytes.length);
      } else {
        Buffer.from(body).copy(buf, 10 + headerBytes.length);
      }
    }
    return buf;
  }

  _onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    this._tryDecode();
  }

  _tryDecode() {
    while (this.buffer.length >= FIXED_HEADER_LENGTH) {
      const magic = this.buffer.readUInt16BE(0);
      if (magic !== MAGIC) {
        // 跳过损坏数据，每跳一个字节
        this.buffer = this.buffer.subarray(1);
        continue;
      }

      const bodyLen = this.buffer.readUInt32BE(4);
      const headerLen = this.buffer.readUInt16BE(8);

      if (bodyLen < 0 || bodyLen > 4 * 1024 * 1024) {
        this.buffer = this.buffer.subarray(1);
        continue;
      }

      const totalLen = FIXED_HEADER_LENGTH + bodyLen;
      if (this.buffer.length < totalLen) break; // 数据未到齐

      const frameBuf = this.buffer.subarray(FIXED_HEADER_LENGTH, FIXED_HEADER_LENGTH + bodyLen);
      this.buffer = this.buffer.subarray(totalLen);

      // 解析 header JSON
      const headerStr = frameBuf.subarray(0, headerLen).toString('utf-8');
      let header;
      try {
        header = JSON.parse(headerStr);
      } catch {
        continue;
      }

      if (this.pendingResolve) {
        // 尝试匹配 pending 请求
        const op = parseInt(header._op);
        const expectedOps = this.pendingExpectedOps;
        const matched = !expectedOps || expectedOps.length === 0 || expectedOps.includes(op);
        if (matched) {
          this._resolvePending(header);
        } else if (this.onMessage) {
          this.onMessage(header, frameBuf.subarray(headerLen));
        }
      } else if (this.onMessage) {
        this.onMessage(header, frameBuf.subarray(headerLen));
      }
    }
  }

  _waitForSeq(seq, expectedOps, timeoutMs) {
    return new Promise((resolve, reject) => {
      this.pendingResolve = (header) => {
        // 过滤：只接收期望 op 的响应
        const op = parseInt(header._op);
        if (expectedOps && expectedOps.length > 0 && !expectedOps.includes(op)) {
          return;
        }
        resolve(header);
      };
      this.pendingSeq = seq;
      this.pendingExpectedOps = expectedOps;
      this.pendingTimeout = setTimeout(() => {
        this.pendingResolve = null;
        this.pendingExpectedOps = null;
        reject(new Error(`TIMEOUT: seq=${seq} expectedOp=[${(expectedOps||[]).join(',')}] after ${timeoutMs}ms`));
      }, timeoutMs);
    });
  }

  _resolvePending(header) {
    if (this.pendingTimeout) {
      clearTimeout(this.pendingTimeout);
      this.pendingTimeout = null;
    }
    const resolve = this.pendingResolve;
    this.pendingResolve = null;
    this.pendingExpectedOps = null;
    if (resolve) resolve(header);
  }
}

module.exports = { IMClient, IMError, CMD };
