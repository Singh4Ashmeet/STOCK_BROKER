#!/usr/bin/env bash
grep -rnE "unwrap\(|expect\(|panic!|todo!|unimplemented!" rust/core/src 2>/dev/null | grep -v "^.*://" | grep -v "#\[test\]"