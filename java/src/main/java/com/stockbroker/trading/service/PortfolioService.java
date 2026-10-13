package com.stockbroker.trading.service;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.service.MarketDataService;
import com.stockbroker.trading.api.TradingDtos;
import com.stockbroker.trading.api.TradingDtosExtended;
import com.stockbroker.trading.domain.Account;
import com.stockbroker.trading.domain.Position;
import com.stockbroker.trading.repo.AccountRepository;
import com.stockbroker.trading.repo.LedgerRepository;
import com.stockbroker.trading.repo.PositionRepository;
import com.stockbroker.portfolio.domain.Watchlist;
import com.stockbroker.portfolio.domain.WatchlistItem;
import com.stockbroker.portfolio.repo.WatchlistRepository;
import com.stockbroker.portfolio.repo.WatchlistItemRepository;
import com.stockbroker.portfolio.api.PortfolioDtos;
import com.stockbroker.portfolio.api.PortfolioDtosExtended;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Portfolio service - handles positions, P&L, transaction history, and watchlists.
 * All queries are user_id-filtered for isolation.
 */
@Service
@Transactional(readOnly = true)
public class PortfolioService {

    private final PositionRepository positionRepository;
    private final LedgerRepository ledgerRepository;
    private final LedgerService ledgerService;
    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final MarketDataService marketDataService;
    private final WatchlistRepository watchlistRepository;
    private final WatchlistItemRepository watchlistItemRepository;

    public PortfolioService(PositionRepository positionRepository,
                            LedgerRepository ledgerRepository,
                            LedgerService ledgerService,
                            AccountService accountService,
                            AccountRepository accountRepository,
                            MarketDataService marketDataService,
                            WatchlistRepository watchlistRepository,
                            WatchlistItemRepository watchlistItemRepository) {
        this.positionRepository = positionRepository;
        this.ledgerRepository = ledgerRepository;
        this.ledgerService = ledgerService;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.marketDataService = marketDataService;
        this.watchlistRepository = watchlistRepository;
        this.watchlistItemRepository = watchlistItemRepository;
    }

    // ==================== PORTFOLIO ====================

    public TradingDtos.PortfolioResponse getPortfolio(UUID userId) {
        // Get user's default account
        Account account = accountService.getOrCreateDefaultAccount(userId);
        UUID accountId = account.getId();

        // Get positions
        List<Position> positions = positionRepository.findByAccountId(accountId);

        // Calculate portfolio with live prices
        BigDecimal totalEquity = BigDecimal.ZERO;
        BigDecimal cashBalance = ledgerService.getCashBalance(accountId, account.getBaseCcy());
        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        BigDecimal realizedPnl = BigDecimal.ZERO;

        List<TradingDtos.PositionResponse> positionResponses = positions.stream()
                .map(this::enrichPositionWithLiveData)
                .collect(Collectors.toList());

        // Sum up totals
        for (TradingDtos.PositionResponse pos : positionResponses) {
            totalEquity = totalEquity.add(new BigDecimal(pos.marketValue()));
            unrealizedPnl = unrealizedPnl.add(new BigDecimal(pos.unrealizedPnl()));
            realizedPnl = realizedPnl.add(new BigDecimal(pos.realizedPnl()));
        }

        totalEquity = totalEquity.add(cashBalance);

        return new TradingDtos.PortfolioResponse(
                totalEquity.stripTrailingZeros().toPlainString(),
                cashBalance.stripTrailingZeros().toPlainString(),
                unrealizedPnl.stripTrailingZeros().toPlainString(),
                realizedPnl.stripTrailingZeros().toPlainString(),
                positionResponses
        );
    }

    private TradingDtos.PositionResponse enrichPositionWithLiveData(Position pos) {
        // Get live price from market data
        var snapshot = marketDataService.getSnapshot(pos.getSymbol(), 1);
        BigDecimal currentPrice = BigDecimal.ZERO;
        
        if (snapshot != null && snapshot.snapshot() != null && !snapshot.snapshot().asks().isEmpty()) {
            currentPrice = new BigDecimal(snapshot.snapshot().asks().get(0).price());
        } else if (snapshot != null && snapshot.snapshot() != null && !snapshot.snapshot().bids().isEmpty()) {
            currentPrice = new BigDecimal(snapshot.snapshot().bids().get(0).price());
        }

        BigDecimal qty = pos.getQty();
        BigDecimal avgCost = pos.getAvgCost();
        BigDecimal marketValue = qty.multiply(currentPrice);
        BigDecimal positionUnrealized = qty.multiply(currentPrice.subtract(avgCost));

        return new TradingDtos.PositionResponse(
                pos.getSymbol(),
                qty.stripTrailingZeros().toPlainString(),
                avgCost.stripTrailingZeros().toPlainString(),
                currentPrice.stripTrailingZeros().toPlainString(),
                positionUnrealized.stripTrailingZeros().toPlainString(),
                pos.getRealizedPnl().stripTrailingZeros().toPlainString(),
                marketValue.stripTrailingZeros().toPlainString()
        );
    }

    // ==================== TRANSACTION HISTORY ====================

    public TradingDtos.LedgerResponse getLedger(UUID userId,
                                                 String from,
                                                 String to,
                                                 int limit,
                                                 String cursor) {
        Account account = accountService.getOrCreateDefaultAccount(userId);
        
        // Parse cursor for pagination
        Instant cursorTime = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                cursorTime = Instant.parse(cursor);
            } catch (Exception e) {
                throw new BusinessProblem.ValidationFailed("Invalid cursor format");
            }
        }

        Instant fromTime = from != null ? Instant.parse(from) : Instant.EPOCH;
        Instant toTime = to != null ? Instant.parse(to) : Instant.now();
        
        // Apply cursor as upper bound if present (for reverse chronological pagination)
        if (cursorTime != null && cursorTime.isBefore(toTime)) {
            toTime = cursorTime;
        }

        List<Object[]> entries = ledgerRepository.findByUserIdAndTimeRange(
                userId, fromTime, toTime, limit + 1); // +1 to detect hasMore

        boolean hasMore = entries.size() > limit;
        if (hasMore) {
            entries = entries.subList(0, limit);
        }

        List<TradingDtos.LedgerEntryResponse> responses = entries.stream()
                .map(e -> new TradingDtos.LedgerEntryResponse(
                        e[0].toString(), // id
                        e[1].toString(), // asset
                        e[2].toString(), // amount
                        e[3].toString(), // type
                        e[4] != null ? e[4].toString() : null, // ref_type
                        e[5] != null ? e[5].toString() : null, // ref_id
                        e[6].toString()  // created_at
                ))
                .collect(Collectors.toList());

        String nextCursor = hasMore && !entries.isEmpty() 
                ? entries.get(entries.size() - 1)[6].toString() 
                : null;

        return new TradingDtos.LedgerResponse(responses, nextCursor);
    }

    // ==================== WATCHLISTS WITH LIVE PRICES ====================

    public PortfolioDtosExtended.WatchlistListResponse getWatchlistsWithLivePrices(UUID userId) {
        List<Watchlist> watchlists = watchlistRepository.findByUserId(userId);
        List<PortfolioDtos.WatchlistResponse> responses = watchlists.stream()
                .map(this::enrichWatchlistWithLivePrices)
                .collect(Collectors.toList());
        return new PortfolioDtosExtended.WatchlistListResponse(responses);
    }

    private PortfolioDtos.WatchlistResponse enrichWatchlistWithLivePrices(Watchlist watchlist) {
        List<WatchlistItem> items = watchlistItemRepository.findByWatchlistId(watchlist.getId());
        List<PortfolioDtos.WatchlistItemResponse> enrichedItems = items.stream()
                .map(item -> {
                    // Get live price for each symbol
                    var snapshot = marketDataService.getSnapshot(item.getSymbol(), 1);
                    BigDecimal currentPrice = BigDecimal.ZERO;
                    BigDecimal change24h = BigDecimal.ZERO;
                    
                    if (snapshot != null && snapshot.snapshot() != null) {
                        if (!snapshot.snapshot().asks().isEmpty()) {
                            currentPrice = new BigDecimal(snapshot.snapshot().asks().get(0).price());
                        } else if (!snapshot.snapshot().bids().isEmpty()) {
                            currentPrice = new BigDecimal(snapshot.snapshot().bids().get(0).price());
                        }
                    }
                    
                    return new PortfolioDtos.WatchlistItemResponse(
                            item.getSymbol(),
                            item.getPosition(),
                            currentPrice.stripTrailingZeros().toPlainString(),
                            change24h.stripTrailingZeros().toPlainString()
                    );
                })
                .collect(Collectors.toList());

        return new PortfolioDtos.WatchlistResponse(
                watchlist.getId().toString(),
                watchlist.getName(),
                watchlist.getCreatedAt().toString(),
                enrichedItems
        );
    }

    // ==================== HELPER METHODS ====================

    public BigDecimal getCashBalance(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Account not found"));
        return ledgerService.getCashBalance(accountId, account.getBaseCcy());
    }
}