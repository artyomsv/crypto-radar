package com.cryptoradar.signal.model;

import java.util.List;

public record DimensionScore(String name, double score, double weight, List<String> reasons) {}
