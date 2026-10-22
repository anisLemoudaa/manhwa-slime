const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const bridgePath = path.join(
  __dirname,
  "../../app/src/main/assets/lnhost/request-bridge.js",
);
const bridgeSource = fs.readFileSync(bridgePath, "utf8");

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function makeBridgeContext() {
  const requests = new Map();
  const context = {
    __lnCallMethod(_pluginId, _method, argsJson) {
      const requestName = JSON.parse(argsJson)[0];
      const operation = deferred();
      requests.set(requestName, operation);
      return operation.promise;
    },
  };
  vm.createContext(context);
  vm.runInContext(bridgeSource, context);
  return { context, requests };
}

async function flushPromises() {
  await new Promise((resolve) => setImmediate(resolve));
}

test("late result from a cancelled chapter cannot replace a newer request", async () => {
  const { context, requests } = makeBridgeContext();
  const args = (name) => JSON.stringify([name]);

  context.__lnBeginRequest("request-old", "source", "parseChapter", args("old"));
  context.__lnBeginRequest("request-new", "source", "parseChapter", args("new"));
  await flushPromises();

  requests.get("new").resolve(JSON.stringify({ ok: true, value: "new chapter" }));
  await flushPromises();
  assert.equal(
    context.__lnGetRequestState("request-new"),
    JSON.stringify({ ok: true, value: "new chapter" }),
  );

  requests.get("old").resolve(JSON.stringify({ ok: true, value: "old chapter" }));
  await flushPromises();
  assert.equal(
    context.__lnGetRequestState("request-new"),
    JSON.stringify({ ok: true, value: "new chapter" }),
  );
  assert.equal(
    context.__lnGetRequestState("request-old"),
    JSON.stringify({ ok: true, value: "old chapter" }),
  );
});

test("a forgotten request stays forgotten when its promise resolves later", async () => {
  const { context, requests } = makeBridgeContext();
  context.__lnBeginRequest("cancelled", "source", "parseChapter", JSON.stringify(["old"]));
  await flushPromises();

  context.__lnForgetRequest("cancelled");
  requests.get("old").resolve(JSON.stringify({ ok: true, value: "stale" }));
  await flushPromises();

  assert.equal(context.__lnGetRequestState("cancelled"), "__missing__");
});
