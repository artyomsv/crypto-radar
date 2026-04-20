import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { ExchangeAccount, CreateAccountRequest, UpdateAccountRequest } from '@/types';

interface CreateResult {
  success: true;
  account: ExchangeAccount;
}
interface ErrorResult {
  success: false;
  error: string;
  status: number;
}

export function useExecutionAccounts() {
  const [accounts, setAccounts] = useState<ExchangeAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    const data = await api.execution.listAccounts();
    if (data) setAccounts(data);
    else setError('Failed to load exchange accounts');
    setLoading(false);
  }, []);

  const create = useCallback(async (req: CreateAccountRequest): Promise<CreateResult | ErrorResult> => {
    const { data, error, status } = await api.execution.createAccount(req);
    if (data) {
      setAccounts((prev) => [...prev, data]);
      return { success: true, account: data };
    }
    return { success: false, error: error ?? `HTTP ${status}`, status };
  }, []);

  const patch = useCallback(async (id: number, req: UpdateAccountRequest): Promise<CreateResult | ErrorResult> => {
    const { data, error, status } = await api.execution.patchAccount(id, req);
    if (data) {
      setAccounts((prev) => prev.map((a) => (a.id === id ? data : a)));
      return { success: true, account: data };
    }
    return { success: false, error: error ?? `HTTP ${status}`, status };
  }, []);

  const remove = useCallback(async (id: number): Promise<{ success: true } | ErrorResult> => {
    const { error, status } = await api.execution.deleteAccount(id);
    if (error) return { success: false, error, status };
    setAccounts((prev) => prev.filter((a) => a.id !== id));
    return { success: true };
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { accounts, loading, error, refresh, create, patch, remove };
}
