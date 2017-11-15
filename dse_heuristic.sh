#!/usr/bin/env bash

benchmarks=("BlackScholes", "DotProduct", "GDA", "Kmeans", "MatMult_outer",  "OuterProduct", "Sobel", "SW", "TPCHQ6")

for benchmark in "${benchmarks[@]}"
do
    echo "bin/spatial $benchmark --experiment --t 4"
    bin/spatial $benchmark --experiment --t 4
done
