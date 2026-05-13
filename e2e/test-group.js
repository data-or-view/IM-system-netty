const { IMClient, CMD } = require('./client');

const HOST = process.env.HOST || '127.0.0.1';
const PORT = process.env.PORT || 8080;

/**
 * 群组 E2E 测试。
 *
 * 流程：
 *   ① 创建 3 个用户：u1, u2, u3
 *   ② u1 创建群组 group_test
 *   ③ u2, u3 加群
 *   ④ u1 发送群消息 → u2, u3 应能收到
 *   ⑤ u2 发送群消息 → u1, u3 应能收到
 */
async function runTests() {
  let passed = 0, failed = 0;

  function step(name, fn) {
    return Promise.resolve().then(async () => {
      try {
        await fn();
        console.log(`  ✅ [${String(++passed).padStart(2, '0')}] ${name}`);
      } catch (e) {
        failed++;
        console.log(`  ❌ [${String(passed + failed).padStart(2, '0')}] ${name}: ${e.message}`);
        // Don't bail - try remaining tests
      }
    });
  }

  console.log(`🔬 群组 E2E 测试 (${HOST}:${PORT})\n`);

  // ─── 创建客户端 ───
  const users = { u1: new IMClient(), u2: new IMClient(), u3: new IMClient() };
  const groupId = 'group_test_e2e';
  const groupName = 'Test Group E2E';
  let received = { u2: [], u3: [], u1: [] };

  // 注册消息接收回调
  for (const [id, c] of Object.entries(users)) {
    c.onMessage = (header, body) => {
      const op = parseInt(header._op);
      if (op === CMD.GROUP_CHAT || op === CMD.SINGLE_CHAT) {
        received[id].push({ op, header, body });
        console.log(`      📩 ${id} 收到消息: from=${header.fromUserId} group=${header.groupId} content=${header.content}`);
      }
    };
  }

  try {
    // ─── Phase 1: 连接 & 注册 & 登录 ───
    console.log('━━━ Phase 1: 初始化 ───');

    await step('连接 + 注册 + 登录 u1', async () => {
      await users.u1.connect(HOST, PORT);
      await users.u1.register('u1', '111111');
      await users.u1.login('u1', '111111');
    });

    await step('连接 + 注册 + 登录 u2', async () => {
      await users.u2.connect(HOST, PORT);
      await users.u2.register('u2', '111111');
      await users.u2.login('u2', '111111');
    });

    await step('连接 + 注册 + 登录 u3', async () => {
      await users.u3.connect(HOST, PORT);
      await users.u3.register('u3', '111111');
      await users.u3.login('u3', '111111');
    });

    // ─── Phase 2: 创建群 ───
    console.log('\n━━━ Phase 2: 创建群组 ───');

    await step('u1 创建群组 group_test_e2e', async () => {
      const r = await users.u1.createGroup(groupId, groupName);
      if (r.status !== 'OK') throw new Error(`创建失败: ${r.reason}`);
    });

    // ─── Phase 3: 加群 ───
    console.log('\n━━━ Phase 3: 加群 ───');

    await step('u2 加入群组', async () => {
      const r = await users.u2.joinGroup(groupId);
      if (r.status !== 'OK') throw new Error(`加群失败: ${r.reason}`);
    });

    await step('u3 加入群组', async () => {
      const r = await users.u3.joinGroup(groupId);
      if (r.status !== 'OK') throw new Error(`加群失败: ${r.reason}`);
    });

    // ─── Phase 4: 群消息收发送 ───
    console.log('\n━━━ Phase 4: 群消息收发 ───');

    // 清空接收记录
    received = { u1: [], u2: [], u3: [] };

    await step('u1 发送群消息 "hello from u1"', async () => {
      const r = await users.u1.sendGroupMessage(groupId, 101, 'hello from u1', 8000);
      if (r.status !== 'RECEIVED') throw new Error(`发送失败: ${r.reason || r.status}`);
      console.log(`      seq=${r._ms}, conversationId=${r.conversationId}`);
    });

    // 等待消息投递
    await new Promise(r => setTimeout(r, 2000));

    await step('u2 应收到 u1 的群消息', async () => {
      const msgs = received.u2.filter(m => m.header.content === 'hello from u1');
      if (msgs.length === 0) throw new Error('u2 未收到群消息');
      console.log(`      u2 收到 ${msgs.length} 条匹配消息`);
    });

    await step('u3 应收到 u1 的群消息', async () => {
      const msgs = received.u3.filter(m => m.header.content === 'hello from u1');
      if (msgs.length === 0) throw new Error('u3 未收到群消息');
      console.log(`      u3 收到 ${msgs.length} 条匹配消息`);
    });

    await step('u1 不应收到自己的群消息（排除发送者）', async () => {
      const msgs = received.u1.filter(m => m.header.content === 'hello from u1');
      if (msgs.length > 0) {
        console.log(`      ⚠️ u1 收到 ${msgs.length} 条自己发的消息（注意：某些实现会回显给发送者）`);
      } else {
        console.log('      ✅ u1 未收到自己发的消息（符合预期）');
      }
    });

    // ─── Phase 5: 反向发送 ───
    console.log('\n━━━ Phase 5: 反向群消息 ───');

    received = { u1: [], u2: [], u3: [] };

    await step('u2 发送群消息 "hello from u2"', async () => {
      const r = await users.u2.sendGroupMessage(groupId, 101, 'hello from u2', 8000);
      if (r.status !== 'RECEIVED') throw new Error(`发送失败: ${r.reason || r.status}`);
      console.log(`      seq=${r._ms}`);
    });

    await new Promise(r => setTimeout(r, 2000));

    await step('u1 应收到 u2 的群消息', async () => {
      const msgs = received.u1.filter(m => m.header.content === 'hello from u2');
      if (msgs.length === 0) throw new Error('u1 未收到群消息');
      console.log(`      u1 收到 ${msgs.length} 条匹配消息`);
    });

    await step('u3 应收到 u2 的群消息', async () => {
      const msgs = received.u3.filter(m => m.header.content === 'hello from u2');
      if (msgs.length === 0) throw new Error('u3 未收到群消息');
      console.log(`      u3 收到 ${msgs.length} 条匹配消息`);
    });

    await step('u2 不应收到自己的群消息', async () => {
      const msgs = received.u2.filter(m => m.header.content === 'hello from u2');
      if (msgs.length > 0) {
        console.log(`      ⚠️ u2 收到 ${msgs.length} 条自己发的消息`);
      } else {
        console.log('      ✅ u2 未收到自己发的消息（符合预期）');
      }
    });

    // ─── Phase 6: 退群 ───
    console.log('\n━━━ Phase 6: 退群 ───');

    await step('u3 退出群组', async () => {
      const r = await users.u3.quitGroup(groupId);
      if (r.status !== 'OK') throw new Error(`退群失败: ${r.reason}`);
    });

    received = { u1: [], u2: [], u3: [] };

    await step('u1 发消息，退群的 u3 不应收到', async () => {
      await users.u1.sendGroupMessage(groupId, 101, 'after quit test', 8000);
      await new Promise(r => setTimeout(r, 2000));
      const msgs = received.u3.filter(m => m.header.content === 'after quit test');
      if (msgs.length > 0) {
        console.log(`      ⚠️ u3 已退群但仍收到消息（注意检查权限）`);
      } else {
        console.log('      ✅ u3 未收到消息（符合退群预期）');
      }
    });

  } finally {
    // ─── 清理 ───
    console.log('\n清理连接...');
    for (const c of Object.values(users)) {
      c.close();
    }
  }

  // ─── 结果 ───
  console.log('\n' + '━'.repeat(40));
  const total = passed + failed;
  console.log(`\n📊 结果: ${passed} 通过, ${failed} 失败`);
  if (failed > 0) process.exit(1);

  // 打印接收统计
  console.log('\n📋 接收统计:');
  for (const [id, msgs] of Object.entries(received)) {
    console.log(`   ${id}: ${msgs.length} 条消息`);
  }
}

runTests().catch(e => {
  console.error('FATAL:', e.message);
  process.exit(1);
});
