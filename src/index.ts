import Fastify from "fastify";
import * as api from "@actual-app/api";
import { parseTransaction } from "./parser.js";

const fastify = Fastify({ logger: true });

async function initActual() {
  await api.init({
    dataDir: "/app/data",
    serverURL: process.env.ACTUAL_SERVER_URL!,
    password: process.env.ACTUAL_PASSWORD!,
  });
  await api.downloadBudget(process.env.ACTUAL_SYNC_ID!);
}

function getSenderFromPayload(body: string): string {
  const match = body.match(/^From:\s*(.+@.+)$/im);
  if (match) return match[1].trim();
  throw new Error("Could not extract sender from email payload");
}

fastify.post("/webhook", async (req) => {
  const rawEmail = req.body as string;

  try {
    const sender = getSenderFromPayload(rawEmail);
    const tx = parseTransaction(rawEmail, sender);
    const centsAmount = Math.round(tx.amount * -100);

    await api.addTransactions(process.env.ACTUAL_ACCOUNT_ID!, [
      {
        date: new Date().toISOString().split("T")[0],
        amount: centsAmount,
        payee_name: tx.payee,
        cleared: true,
      },
    ]);

    return { status: "synced" };
  } catch (err) {
    fastify.log.error(err);
    return { error: (err as Error).message };
  }
});

const start = async () => {
  fastify.addContentTypeParser(
    "*",
    { parseAs: "string" },
    (_req, _payload, done) => done(null),
  );
  await initActual();
  await fastify.listen({ port: 8080, host: "0.0.0.0" });
};

void start();
