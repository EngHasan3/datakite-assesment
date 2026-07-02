"use client";

import { Landmark } from "lucide-react";

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { cn } from "@/lib/utils";
import type { Account } from "@/lib/types";

const LEDGER_CURRENCY = "USD";

const currencyFormatter = new Intl.NumberFormat("en-US", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function AccountsOverview({
  accounts,
  loading,
  className,
}: {
  accounts: Account[];
  loading: boolean;
  className?: string;
}) {
  return (
    <Card className={cn(className)}>
      <CardHeader>
        <CardTitle>Accounts</CardTitle>
        <CardDescription>Live balances across the ledger</CardDescription>
      </CardHeader>
      <CardContent>
        {loading && accounts.length === 0 ? (
          <p className="text-sm text-muted-foreground">Loading accounts...</p>
        ) : accounts.length === 0 ? (
          <p className="text-sm text-muted-foreground">No accounts yet.</p>
        ) : (
          <ul className="grid gap-2 sm:grid-cols-2">
            {accounts.map((account) => (
              <li
                key={account.id}
                className="flex items-center justify-between gap-3 rounded-lg border border-border/60 px-3 py-2.5"
              >
                <div className="flex items-center gap-2.5">
                  <span className="flex size-8 items-center justify-center rounded-full bg-muted text-muted-foreground">
                    <Landmark className="size-4" />
                  </span>
                  <span className="font-mono text-sm">{account.accountNumber}</span>
                </div>
                <span className="font-mono text-sm font-medium tabular-nums">
                  {currencyFormatter.format(account.balance)}{" "}
                  <span className="text-muted-foreground">{LEDGER_CURRENCY}</span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
