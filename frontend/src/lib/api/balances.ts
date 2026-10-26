import { z } from 'zod';

const balanceSchema = z.object({
  asset: z.string().min(1),
  total: z.string(),
}).strict();

const balancesResponseSchema = z.array(balanceSchema);

export function parseBalancesResponse(response: unknown) {
  return balancesResponseSchema.parse(response);
}
