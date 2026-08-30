import { Module } from "@nestjs/common";
import { BillingController } from "./billing.controller";
import { BillingService } from "./billing.service";
import { LedgerService } from "./ledger.service";
import { WalletService } from "./wallet.service";

@Module({
  controllers: [BillingController],
  providers: [BillingService, LedgerService, WalletService],
  exports: [BillingService, WalletService, LedgerService], // 网关（Step5/6）/充值（Step7）统一从这里扣/入账
})
export class BillingModule {}
