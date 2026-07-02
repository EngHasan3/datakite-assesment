"use client";

import { useState } from "react";
import { toast } from "sonner";
import { ChevronLeft, ChevronRight } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ApiRequestError, releaseTransaction, rejectTransaction } from "@/lib/api";
import type { Account, PageResponse, Transaction, TransactionStatus } from "@/lib/types";

const STATUS_OPTIONS: { value: TransactionStatus | "ALL"; label: string }[] = [
  { value: "ALL", label: "All statuses" },
  { value: "COMPLETED", label: "Completed" },
  { value: "PENDING_REVIEW", label: "Pending review" },
  { value: "REJECTED", label: "Rejected" },
  { value: "FAILED", label: "Failed" },
];

const STATUS_BADGE_VARIANT: Record<TransactionStatus, "default" | "secondary" | "destructive" | "outline"> = {
  COMPLETED: "secondary",
  PENDING_REVIEW: "default",
  REJECTED: "destructive",
  FAILED: "destructive",
};

const dateFormatter = new Intl.DateTimeFormat("en-US", {
  dateStyle: "medium",
  timeStyle: "short",
});

export function TransactionsTable({
  page,
  accounts,
  loading,
  statusFilter,
  onStatusFilterChange,
  onPageChange,
  onChanged,
}: {
  page: PageResponse<Transaction> | null;
  accounts: Account[];
  loading: boolean;
  statusFilter: TransactionStatus | "ALL";
  onStatusFilterChange: (status: TransactionStatus | "ALL") => void;
  onPageChange: (page: number) => void;
  onChanged: () => void;
}) {
  const [actingOn, setActingOn] = useState<string | null>(null);
  const accountNumberById = new Map(accounts.map((a) => [a.id, a.accountNumber]));

  async function handleRelease(transaction: Transaction) {
    setActingOn(transaction.id);
    try {
      await releaseTransaction(transaction.id);
      toast.success("Transfer released", { description: "Funds have moved." });
      onChanged();
    } catch (error) {
      toast.error(error instanceof ApiRequestError ? error.message : "Failed to release transfer");
    } finally {
      setActingOn(null);
    }
  }

  async function handleReject(transaction: Transaction) {
    setActingOn(transaction.id);
    try {
      await rejectTransaction(transaction.id);
      toast.success("Transfer rejected", { description: "No funds were moved." });
      onChanged();
    } catch (error) {
      toast.error(error instanceof ApiRequestError ? error.message : "Failed to reject transfer");
    } finally {
      setActingOn(null);
    }
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <div>
          <CardTitle>Ledger</CardTitle>
          <CardDescription>Paginated transaction history</CardDescription>
        </div>
        <Select
          value={statusFilter}
          onValueChange={(value) => onStatusFilterChange(value as TransactionStatus | "ALL")}
        >
          <SelectTrigger className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Date</TableHead>
              <TableHead>Source</TableHead>
              <TableHead>Destination</TableHead>
              <TableHead>Amount</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Idempotency key</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {!loading && page?.content.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-muted-foreground">
                  No transactions found.
                </TableCell>
              </TableRow>
            )}
            {page?.content.map((transaction) => (
              <TableRow key={transaction.id}>
                <TableCell className="text-muted-foreground">
                  {dateFormatter.format(new Date(transaction.date))}
                </TableCell>
                <TableCell className="font-mono">
                  {accountNumberById.get(transaction.sourceAccountId) ?? transaction.sourceAccountId.slice(0, 8)}
                </TableCell>
                <TableCell className="font-mono">
                  {accountNumberById.get(transaction.destinationAccountId) ?? transaction.destinationAccountId.slice(0, 8)}
                </TableCell>
                <TableCell className="font-mono tabular-nums">
                  {transaction.amount.toFixed(2)} {transaction.currency}
                </TableCell>
                <TableCell>
                  <Badge variant={STATUS_BADGE_VARIANT[transaction.status]}>
                    {transaction.status.replace("_", " ")}
                  </Badge>
                </TableCell>
                <TableCell
                  className="max-w-32 truncate font-mono text-xs text-muted-foreground"
                  title={transaction.idempotencyKey}
                >
                  {transaction.idempotencyKey}
                </TableCell>
                <TableCell className="text-right">
                  {transaction.status === "PENDING_REVIEW" && (
                    <div className="flex justify-end gap-2">
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={actingOn === transaction.id}
                        onClick={() => handleRelease(transaction)}
                      >
                        Release
                      </Button>
                      <Button
                        size="sm"
                        variant="destructive"
                        disabled={actingOn === transaction.id}
                        onClick={() => handleReject(transaction)}
                      >
                        Reject
                      </Button>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>

        {page && page.totalPages > 1 && (
          <div className="mt-4 flex items-center justify-between">
            <p className="text-sm text-muted-foreground">
              Page {page.page + 1} of {page.totalPages} &middot; {page.totalElements} total
            </p>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={page.page <= 0}
                onClick={() => onPageChange(page.page - 1)}
              >
                <ChevronLeft />
                Previous
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={page.page + 1 >= page.totalPages}
                onClick={() => onPageChange(page.page + 1)}
              >
                Next
                <ChevronRight />
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
