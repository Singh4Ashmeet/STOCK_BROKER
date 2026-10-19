package com.stockbroker.portfolio.service;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.market.service.MarketDataService;
import com.stockbroker.portfolio.api.PortfolioDtos;
import com.stockbroker.portfolio.api.PortfolioDtosExtended;
import com.stockbroker.portfolio.domain.Watchlist;
import com.stockbroker.portfolio.domain.WatchlistItem;
import com.stockbroker.portfolio.repo.WatchlistRepository;
import com.stockbroker.portfolio.repo.WatchlistItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Watchlist service with live price enrichment.
 */
@Service
@Transactional
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final WatchlistItemRepository itemRepository;
    private final MarketDataService marketDataService;

    public WatchlistService(WatchlistRepository watchlistRepository,
                            WatchlistItemRepository itemRepository,
                            MarketDataService marketDataService) {
        this.watchlistRepository = watchlistRepository;
        this.itemRepository = itemRepository;
        this.marketDataService = marketDataService;
    }

    public PortfolioDtosExtended.WatchlistListResponse listWatchlists(UUID userId) {
        List<Watchlist> watchlists = watchlistRepository.findByUserId(userId);
        List<PortfolioDtos.WatchlistResponse> responses = watchlists.stream()
                .map(this::toWatchlistResponse)
                .collect(Collectors.toList());
        return new PortfolioDtosExtended.WatchlistListResponse(responses);
    }

    public PortfolioDtos.WatchlistResponse createWatchlist(UUID userId, PortfolioDtos.WatchlistRequest request) {
        Watchlist watchlist = new Watchlist();
        watchlist.setUserId(userId);
        watchlist.setName(request.name());
        watchlistRepository.save(watchlist);
        return toWatchlistResponse(watchlist);
    }

    public PortfolioDtos.WatchlistResponse getWatchlist(UUID userId, UUID watchlistId) {
        Watchlist watchlist = watchlistRepository.findByIdAndUserId(watchlistId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Watchlist not found",
                        java.util.Map.of("code", "WATCHLIST_NOT_FOUND")));
        return toWatchlistResponse(watchlist);
    }

    public void deleteWatchlist(UUID userId, UUID watchlistId) {
        Watchlist watchlist = watchlistRepository.findByIdAndUserId(watchlistId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Watchlist not found",
                        java.util.Map.of("code", "WATCHLIST_NOT_FOUND")));
        watchlistRepository.delete(watchlist);
    }

    public PortfolioDtos.WatchlistItemResponse addItem(UUID userId, UUID watchlistId, PortfolioDtos.WatchlistItemRequest request) {
        watchlistRepository.findByIdAndUserId(watchlistId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Watchlist not found",
                        java.util.Map.of("code", "WATCHLIST_NOT_FOUND")));

        // Check if item already exists
        if (itemRepository.existsByWatchlistIdAndSymbol(watchlistId, request.symbol())) {
            throw new BusinessProblem.ValidationFailed("Symbol already in watchlist",
                    java.util.Map.of("code", "WATCHLIST_ITEM_EXISTS"));
        }

        WatchlistItem item = new WatchlistItem();
        item.setWatchlistId(watchlistId);
        item.setSymbol(request.symbol());
        item.setPosition(request.position());
        itemRepository.save(item);

        // Enrich with live price
        BigDecimal currentPrice = getCurrentPrice(request.symbol());
        return new PortfolioDtos.WatchlistItemResponse(
                request.symbol(), 
                request.position(),
                currentPrice.stripTrailingZeros().toPlainString(),
                BigDecimal.ZERO.stripTrailingZeros().toPlainString()
        );
    }

    public void removeItem(UUID userId, UUID watchlistId, String symbol) {
        watchlistRepository.findByIdAndUserId(watchlistId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Watchlist not found",
                        java.util.Map.of("code", "WATCHLIST_NOT_FOUND")));

        itemRepository.deleteByWatchlistIdAndSymbol(watchlistId, symbol);
    }

    private BigDecimal getCurrentPrice(String symbol) {
        var snapshot = marketDataService.getSnapshot(symbol, 1);
        if (snapshot != null && snapshot.snapshot() != null) {
            if (!snapshot.snapshot().asks().isEmpty()) {
                return new BigDecimal(snapshot.snapshot().asks().get(0).price());
            } else if (!snapshot.snapshot().bids().isEmpty()) {
                return new BigDecimal(snapshot.snapshot().bids().get(0).price());
            }
        }
        return BigDecimal.ZERO;
    }

    private PortfolioDtos.WatchlistResponse toWatchlistResponse(Watchlist watchlist) {
        List<PortfolioDtos.WatchlistItemResponse> items = itemRepository.findByWatchlistId(watchlist.getId())
                .stream()
                .map(item -> {
                    BigDecimal currentPrice = getCurrentPrice(item.getSymbol());
                    return new PortfolioDtos.WatchlistItemResponse(
                            item.getSymbol(),
                            item.getPosition(),
                            currentPrice.stripTrailingZeros().toPlainString(),
                            BigDecimal.ZERO.stripTrailingZeros().toPlainString()
                    );
                })
                .collect(Collectors.toList());

        return new PortfolioDtos.WatchlistResponse(
                watchlist.getId().toString(),
                watchlist.getName(),
                watchlist.getCreatedAt().toString(),
                items
        );
    }
}