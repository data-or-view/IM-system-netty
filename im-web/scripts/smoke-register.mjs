/**
 * IM 注册冒烟测试。
 *
 * 测试流程：
 *   ① 连接 WebSocket
 *   ② 发送 REGISTER（userId=test_reg_xxx）
 *   ③ 验证 REGISTER_ACK status=OK
 *   ④ 发送 LOGIN
 *   ⑤ 验证 LOGIN_ACK status=OK + token
 *   ⑥ 再次发送 REGISTER（相同 userId）→ 应为幂等 OK
 *
 * 用法: node scripts/smoke-register.js
 */

import WebSocket from "ws";

const HOST = "localhost";
const PORT = 8081;

const CMD = {
  HEARTBEAT: 0,
  LOGIN: 10,
  LOGIN_ACK: 11,
  REGISTER: 14,
  REGISTER_ACK: 15,
  ERROR: 99,
};

// 二进制帧编码（与 Java IMEncoder 对齐）
function encode(header) {
  const json = JSON.stringify(header);
  const hb = new TextEncoder().encode(json);
  const bodyLen = hb.length; // bodyLen = header JSON 总长度（无额外 body）
  const buf = new ArrayBuffer(10 + bodyLen);
  const dv = new DataView(buf);
  dv.setUint16(0, 0xACAC);   // magic: 2 bytes
  dv.setUint8(2, 1);          // version: 1 byte
  dv.setUint8(3, 0);          // flags: 1 byte
  dv.setUint32(4, bodyLen);   // bodyLen = header JSON 总长度
  dv.setUint16(8, hb.length); // headerLen = 其中 JSON 部分长度
  new Uint8Array(buf, 10).set(hb);  // header JSON starts at byte 10
  return Buffer.from(new Uint8Array(buf));
}

const ws = new WebSocket(`ws://${HOST}:${PORT}/ws`, { binaryType: "nodebuffer" });
const testUserId = "reg_test_" + Date.now();

ws.on("open", () => {
  console.log("✅ Connected");

  // 发送注册
  console.log(`\n📤 REGISTER userId=${testUserId}`);
  ws.send(encode({ _op: String(CMD.REGISTER), userId: testUserId }));
});

ws.on("message", (data) => {
  try {
    const buf = Buffer.from(data);
    const magic = buf.readUInt16BE(0);
    if (magic !== 0xCAFE) return;

    const bodyLen = buf.readUInt32BE(4);
    const headerLen = buf.readUInt16BE(8);
    const headerJson = buf.toString("utf8", 9, 9 + headerLen);
    const header = JSON.parse(headerJson);
    const op = parseInt(header._op || "0");

    console.log(`📥 op=${op} status=${header.status}`, JSON.stringify(header));

    switch (op) {
      case CMD.REGISTER_ACK:
        if (header.status === "OK") {
          console.log("✅ 注册成功！");
          // 自动登录
          console.log(`\n📤 LOGIN userId=${testUserId}`);
          ws.send(encode({ _op: String(CMD.LOGIN), userId: testUserId }));
        } else {
          console.log("❌ 注册失败:", header.reason);
          ws.close();
        }
        break;

      case CMD.LOGIN_ACK:
        if (header.status === "OK" && header.token) {
          console.log(`✅ 登录成功！token=${header.token.substring(0, 20)}...`);
          // 幂等注册测试
          console.log(`\n📤 REGISTER 重复 userId=${testUserId}`);
          ws.send(encode({ _op: String(CMD.REGISTER), userId: testUserId }));
        } else {
          console.log("❌ 登录失败");
          ws.close();
        }
        break;

      case CMD.ERROR:
        console.log("❌ 服务端错误:", header.reason);
        ws.close();
        break;
    }

    // 收到第二次 REGISTER_ACK → 幂等测试通过
    if (op === CMD.REGISTER_ACK && header.status === "OK" && header.token) {
      console.log("✅ 幂等注册测试通过！");
      ws.close();
    }
  } catch (e) {
    console.error("Parse error:", e.message);
  }
});

ws.on("close", () => {
  console.log("\n🔚 测试完成");
  process.exit(0);
});

ws.on("error", (e) => {
  console.error("❌ WS error:", e.message);
  process.exit(1);
});

// 5秒超时
setTimeout(() => {
  console.error("❌ 超时");
  process.exit(1);
}, 5000);
