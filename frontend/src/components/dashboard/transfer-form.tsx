"use client";

import { useState } from "react";
import { toast } from "sonner";
import { ArrowRightLeft, Copy, Send } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ApiRequestError, transfer } from "@/lib/api";
import type { Account, Transaction, TransferRequest } from "@/lib/types";

const CURRENCIES = ["USD", "EUR", "GBP", "JPY"];
const FRAUD_REVIEW_THRESHOLD = 5000;

interface LastSubmission {
  payload: TransferRequest;
  idempotencyKey: string;
  transaction: Transaction;
}

export function TransferForm({
  accounts,
  onTransferred,
}: {
  accounts: Account[];
  onTransferred: () => void;
}) {
  const [sourceAccountNumber, setSourceAccountNumber] = useState("");
  const [destinationAccountNumber, setDestinationAccountNumber] = useState("");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState("USD");
  const [submitting, setSubmitting] = useState(false);
  const [lastSubmission, setLastSubmission] = useState<LastSubmission | null>(null);

  const currentPayload: TransferRequest | null =
    sourceAccountNumber && destinationAccountNumber && amount
      ? {
          sourceAccountNumber,
          destinationAccountNumber,
          amount: Number(amount),
          currency,
        }
      : null;

  const canResendDuplicate =
    lastSubmission !== null &&
    currentPayload !== null &&
    JSON.stringify(lastSubmission.payload) === JSON.stringify(currentPayload);

  async function submit(payload: TransferRequest, idempotencyKey: string, isDuplicate: boolean) {
    setSubmitting(true);
    try {
      const { transaction, replay } = await transfer(payload, idempotencyKey);
      setLastSubmission({ payload, idempotencyKey, transaction });
      onTransferred();

      if (transaction.status === "PENDING_REVIEW") {
        toast.warning(`Flagged for review: amount exceeds $${FRAUD_REVIEW_THRESHOLD.toLocaleString()}`, {
          description: `Transaction ${transaction.id} is paused until an admin releases it.`,
        });
      } else if (isDuplicate) {
        toast[replay ? "success" : "error"](
          replay
            ? "Duplicate detected — cached response returned"
            : "Not a replay — this executed as a new transfer",
          {
            description: replay
              ? `Same transaction (${transaction.id}), balances were not touched again.`
              : `Idempotent-Replay header was false for a resend — this indicates a bug.`,
          }
        );
      } else {
        toast.success("Transfer completed", {
          description: `${payload.amount} ${payload.currency} moved. Transaction ${transaction.id}.`,
        });
      }
    } catch (error) {
      const message = error instanceof ApiRequestError ? error.message : "Transfer failed";
      toast.error(message);
    } finally {
      setSubmitting(false);
    }
  }

  function handleSend() {
    if (!currentPayload) return;
    const idempotencyKey = crypto.randomUUID();
    void submit(currentPayload, idempotencyKey, false);
  }

  function handleResendDuplicate() {
    if (!lastSubmission) return;
    void submit(lastSubmission.payload, lastSubmission.idempotencyKey, true);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>New transfer</CardTitle>
        <CardDescription>
          Every submission carries a fresh Idempotency-Key. Resend the exact same request to prove
          duplicates never move money twice.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="grid gap-4 sm:grid-cols-2"
          onSubmit={(e) => {
            e.preventDefault();
            handleSend();
          }}
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="source">Source account</Label>
            <Select
              value={sourceAccountNumber}
              onValueChange={(value) => setSourceAccountNumber(value ?? "")}
            >
              <SelectTrigger id="source" className="w-full">
                <SelectValue placeholder="Select source" />
              </SelectTrigger>
              <SelectContent>
                {accounts.map((account) => (
                  <SelectItem key={account.id} value={account.accountNumber}>
                    {account.accountNumber}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="destination">Destination account</Label>
            <Select
              value={destinationAccountNumber}
              onValueChange={(value) => setDestinationAccountNumber(value ?? "")}
            >
              <SelectTrigger id="destination" className="w-full">
                <SelectValue placeholder="Select destination" />
              </SelectTrigger>
              <SelectContent>
                {accounts.map((account) => (
                  <SelectItem key={account.id} value={account.accountNumber}>
                    {account.accountNumber}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="amount">Amount</Label>
            <Input
              id="amount"
              type="number"
              min="0.01"
              step="0.01"
              placeholder="0.00"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              className="[appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
            />
            <p className="text-xs text-muted-foreground">
              Amounts over ${FRAUD_REVIEW_THRESHOLD.toLocaleString()} are automatically flagged for
              admin review instead of executing immediately.
            </p>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="currency">Currency</Label>
            <Select value={currency} onValueChange={(value) => setCurrency(value ?? "USD")}>
              <SelectTrigger id="currency" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {CURRENCIES.map((code) => (
                  <SelectItem key={code} value={code}>
                    {code}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-wrap items-center gap-2 sm:col-span-2">
            <Button type="submit" disabled={!currentPayload || submitting}>
              <Send />
              Send transfer
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={!canResendDuplicate || submitting}
              onClick={handleResendDuplicate}
            >
              <Copy />
              Simulate duplicate (same key)
            </Button>
            {lastSubmission && (
              <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <ArrowRightLeft className="size-3.5" />
                Last Idempotency-Key: <code className="font-mono">{lastSubmission.idempotencyKey.slice(0, 8)}...</code>
              </span>
            )}
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
