package com.stockbroker.trading.service;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.trading.domain.Account;
import com.stockbroker.trading.repo.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Account service - manages paper trading accounts.
 */
@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Get or create the default account for a user.
     */
    public Account getOrCreateDefaultAccount(UUID userId) {
        return accountRepository.findByUserIdAndName(userId, "default")
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setUserId(userId);
                    account.setName("default");
                    account.setBaseCcy("USDT");
                    return accountRepository.save(account);
                });
    }

    /**
     * Get all accounts for a user.
     */
    public List<Account> getAccounts(UUID userId) {
        return accountRepository.findByUserId(userId);
    }

    /**
     * Get a specific account by ID (with user_id check).
     */
    public Account getAccount(UUID userId, UUID accountId) {
        return accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Account not found"));
    }

    /**
     * Create a new named account for a user.
     */
    public Account createAccount(UUID userId, String name, String baseCcy) {
        if (accountRepository.findByUserIdAndName(userId, name).isPresent()) {
            throw new BusinessProblem.ValidationFailed("Account with name '" + name + "' already exists");
        }

        Account account = new Account();
        account.setUserId(userId);
        account.setName(name);
        account.setBaseCcy(baseCcy != null ? baseCcy : "USDT");
        return accountRepository.save(account);
    }
}