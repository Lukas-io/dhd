import { describe, expect, it } from "vitest";

import { CodexAppServerClient } from "../src/assistant-companion.js";

describe("DHD App Server thread contract", () => {
  it("resumes a stored thread on a new client", async () => {
    const client = new CodexAppServerClient();
    const internals = client as any;
    const requests: Array<{ method: string; params?: Record<string, unknown> }> = [];

    internals.startProcess = () => {
      internals.child = { pid: 1234, stdin: { destroyed: false } };
    };
    internals.notify = () => undefined;
    internals.request = async (method: string, params?: Record<string, unknown>) => {
      requests.push({ method, params });
      if (method === "initialize") return { result: {} };
      if (method === "thread/resume") return { result: { thread: { id: "legacy-thread" } } };
      if (method === "turn/start") {
        queueMicrotask(() => {
          internals.handleLine(JSON.stringify({
            method: "turn/completed",
            params: { turn: { status: "completed" } }
          }));
        });
        return { result: { turn: { id: "turn-fresh" } } };
      }
      throw new Error(`Unexpected App Server request in test: ${method}`);
    };

    await expect(client.runTurn("use the phone", "legacy-thread", undefined, undefined, "xhigh", true)).resolves.toMatchObject({
      threadId: "legacy-thread"
    });

    expect(requests.map(({ method }) => method)).toEqual([
      "initialize",
      "thread/resume",
      "turn/start"
    ]);
    const dynamicTools = requests[1]?.params?.dynamicTools as Array<Record<string, unknown>>;
    expect(dynamicTools.map((tool) => tool.name)).toEqual([
      "dhd_list_allowed_apps",
      "dhd_browse_app",
      "dhd_list_displays",
      "dhd_close_display",
      "dhd_get_foreground_app",
      "dhd_observe",
      "dhd_open_app",
      "dhd_execute",
      "dhd_execute_sequence",
      "dhd_request_attention"
    ]);
    expect(requests.find(({ method }) => method === "turn/start")?.params?.effort).toBe("xhigh");
    expect(requests.find(({ method }) => method === "turn/start")?.params?.serviceTier).toBe("priority");
  });

  it("starts a fresh thread only after stored-thread resume fails", async () => {
    const client = new CodexAppServerClient();
    const internals = client as any;
    const requests: string[] = [];

    internals.startProcess = () => {
      internals.child = { pid: 1234, stdin: { destroyed: false } };
    };
    internals.notify = () => undefined;
    const readyThreadIds: string[] = [];
    internals.request = async (method: string) => {
      requests.push(method);
      if (method === "initialize") return { result: {} };
      if (method === "thread/resume") throw new Error("thread was deleted");
      if (method === "thread/start") return { result: { thread: { id: "fresh-thread" } } };
      if (method === "turn/start") {
        queueMicrotask(() => {
          internals.handleLine(JSON.stringify({
            method: "turn/completed",
            params: { turn: { status: "completed" } }
          }));
        });
        return { result: { turn: { id: "turn-fresh" } } };
      }
      throw new Error(`Unexpected App Server request in test: ${method}`);
    };

    await expect(
      client.runTurn(
        "use the phone",
        "deleted-thread",
        undefined,
        undefined,
        undefined,
        false,
        undefined,
        async (threadId) => {
          readyThreadIds.push(threadId);
        },
      ),
    ).resolves.toMatchObject({
      threadId: "fresh-thread",
    });

    expect(requests).toEqual([
      "initialize",
      "thread/resume",
      "thread/start",
      "turn/start"
    ]);
    expect(readyThreadIds).toEqual(["fresh-thread"]);
  });

  it("starts a continuation turn without adding a synthetic user message", async () => {
    const client = new CodexAppServerClient();
    const internals = client as any;
    const requests: Array<{ method: string; params?: Record<string, unknown> }> = [];

    internals.startProcess = () => {
      internals.child = { pid: 1234, stdin: { destroyed: false } };
    };
    internals.notify = () => undefined;
    internals.request = async (method: string, params?: Record<string, unknown>) => {
      requests.push({ method, params });
      if (method === "initialize") return { result: {} };
      if (method === "thread/resume") return { result: { thread: { id: "stopped-thread" } } };
      if (method === "turn/start") {
        queueMicrotask(() => {
          internals.handleLine(JSON.stringify({
            method: "turn/completed",
            params: { turn: { status: "completed" } }
          }));
        });
        return { result: { turn: { id: "continuation-turn" } } };
      }
      throw new Error(`Unexpected App Server request in test: ${method}`);
    };

    await expect(
      client.runTurn(
        "",
        "stopped-thread",
        undefined,
        undefined,
        "xhigh",
        false,
        undefined,
        undefined,
        true,
      ),
    ).resolves.toMatchObject({
      threadId: "stopped-thread",
    });

    expect(requests.find(({ method }) => method === "turn/start")?.params?.input).toEqual([]);
  });
});
