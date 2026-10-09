//! Benchmarks for decimal parsing and formatting

use criterion::{Criterion, black_box, criterion_group, criterion_main};
use sb_core::{StackWriter, parse_scaled, to_decimal_string, write_scaled};

fn parse_scaled_bench(c: &mut Criterion) {
    c.bench_function("parse_scaled_binance", |b| {
        b.iter(|| parse_scaled(black_box(b"64321.10000000"), black_box(2)).unwrap())
    });
    c.bench_function("parse_scaled_small", |b| {
        b.iter(|| parse_scaled(black_box(b"0.00100000"), black_box(8)).unwrap())
    });
    c.bench_function("parse_scaled_large", |b| {
        // Max value for scale 18: 9.223372036854775807 * 10^18 = 9223372036854775807 (i64::MAX)
        b.iter(|| parse_scaled(black_box(b"9.223372036854775807"), black_box(18)).unwrap())
    });
}

fn write_scaled_bench(c: &mut Criterion) {
    c.bench_function("write_scaled_2dp", |b| {
        let mut w = StackWriter::new();
        b.iter(|| {
            w.clear();
            write_scaled(black_box(6432110), black_box(2), &mut w).unwrap()
        })
    });
    c.bench_function("write_scaled_0dp", |b| {
        let mut w = StackWriter::new();
        b.iter(|| {
            w.clear();
            write_scaled(black_box(12345), black_box(0), &mut w).unwrap()
        })
    });
}

fn to_decimal_string_bench(c: &mut Criterion) {
    c.bench_function("to_decimal_string_2dp", |b| {
        b.iter(|| to_decimal_string(black_box(6432110), black_box(2)))
    });
}

#[allow(clippy::float_arithmetic)]
fn baseline_f64_parse_round(c: &mut Criterion) {
    // Baseline: parse with f64 then multiply and round (NOT used in production)
    c.bench_function("baseline_f64_parse_round", |b| {
        b.iter(|| {
            let s = black_box("64321.10000000");
            let scale = black_box(2);
            let f: f64 = s.parse().unwrap();
            (f * 10f64.powi(scale as i32)).round() as i64
        })
    });
}

criterion_group!(
    benches,
    parse_scaled_bench,
    write_scaled_bench,
    to_decimal_string_bench,
    baseline_f64_parse_round
);
criterion_main!(benches);
