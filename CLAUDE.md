# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

GraphHopper is a fast and memory-efficient routing engine written in Java that calculates routes, distances, and turn-by-turn instructions using OpenStreetMap data. It supports multiple routing profiles (car, bike, foot, etc.), advanced algorithms like Contraction Hierarchies (CH) for speed optimization, and provides both a Java library and web service.

## Development Commands

### Building and Testing
- **Build entire project**: `mvn clean compile`
- **Run all tests**: `mvn clean test verify`
- **Run single test**: `mvn test -Dtest=TestClassName`
- **Run integration tests**: `mvn verify`
- **Build with dependencies**: `mvn package`

### Code Quality
- **Run checkstyle**: `mvn checkstyle:check`
- **Run forbidden API checks**: `mvn de.thetaphi:forbiddenapis:check`

### Running GraphHopper
- **Start web service**: `java -Ddw.graphhopper.datareader.file=<osm-file> -jar web/target/graphhopper-web-*-jar-with-dependencies.jar server config-example.yml`
- **Using shell script**: `./graphhopper.sh -a web -i <osm-file>`

### Benchmarking
- **Run benchmarks**: `./benchmark/benchmark.sh <graph_dir> <results_dir> <summary_dir> <small_map> <big_map>`
- **Download test maps**: `./benchmark/download_map.sh`

### Individual Module Commands
- **Core module**: `cd core && mvn test`
- **Web module**: `cd web && mvn test`
- **Tools module**: `cd tools && mvn package`

## High-Level Architecture

### Module Structure
GraphHopper is organized as a Maven multi-module project:
- **core/**: Main routing engine, algorithms, graph storage, OSM reading
- **web/**: REST API, web service implementation using Dropwizard
- **web-api/**: API models and interfaces
- **client-hc/**: Java HTTP client for GraphHopper API
- **tools/**: Command-line tools, benchmarking, measurement utilities
- **reader-gtfs/**: Public transit routing with GTFS data
- **map-matching/**: GPS trace to road network matching
- **navigation/**: Turn-by-turn navigation features
- **example/**: Sample applications and usage examples

### Core Components

#### Graph Storage (`core/src/main/java/com/graphhopper/storage/`)
- **BaseGraph**: Core graph data structure storing nodes and edges
- **GraphHopperStorage**: Main storage implementation with memory-mapped or in-memory options
- **DataAccess**: Low-level data storage abstraction (RAM, memory-mapped)
- **LocationIndex**: Spatial index for finding nearby nodes/edges

#### Routing Algorithms (`core/src/main/java/com/graphhopper/routing/`)
- **AStar, Dijkstra**: Classic shortest path algorithms with bidirectional variants
- **Contraction Hierarchies (CH)**: Speed optimization preprocessing
- **Landmarks (LM)**: Alternative speed optimization using A* with landmarks
- **EdgeBasedCH**: Turn-aware routing with edge-based graph preprocessing

#### OSM Data Processing (`core/src/main/java/com/graphhopper/reader/osm/`)
- **OSMReader**: Parses OSM PBF/XML files into graph representation
- **TagParsers**: Extract routing-relevant properties from OSM tags
- **EncodingManager**: Manages vehicle-specific encoded values and parsing

#### Routing Profiles and Weighting (`core/src/main/java/com/graphhopper/routing/`)
- **Profile**: Defines routing behavior (vehicle + weighting + turn costs)
- **CustomModel**: JSON-based routing customization without code changes
- **Weighting**: Calculates edge costs based on distance, time, preferences

### Key Design Patterns

#### Three-Tier Architecture
1. **Import Phase**: OSM data → Graph storage with vehicle-specific encodings
2. **Preprocessing**: Optional speed optimizations (CH/LM preparation)
3. **Query Phase**: Route calculation using prepared graph structures

#### Encoded Values System
- **EncodedValue**: Stores routing properties per edge (speed, access, surface)
- **BooleanEncodedValue**: Binary properties (access allowed/forbidden)
- **DecimalEncodedValue**: Numeric properties (speed limits, weights)
- **EnumEncodedValue**: Categorical values (road class, surface type)

#### Flexible vs Speed vs Hybrid Modes
- **Flexible**: No preprocessing, supports dynamic customization
- **Speed**: CH preprocessing, fastest queries, limited customization
- **Hybrid**: LM preprocessing, balanced speed/flexibility

## Testing Strategy

### Test Organization
- Unit tests in each module's `src/test/java/`
- Integration tests using `*IT.java` naming convention
- GraphHopper uses JUnit 5 for all testing
- Test data in `core/files/` directory

### Key Test Patterns
- **AlgoTester**: Framework for testing routing algorithms with various graph configurations
- **GHUtility**: Test utilities for creating simple test graphs
- **Measurement**: Performance benchmarking and regression testing

## Configuration

### Primary Config File: `config-example.yml`
- **Profiles**: Define vehicle types and routing behavior
- **CH/LM**: Speed optimization settings
- **Import**: OSM data processing options
- **Web**: Server configuration for REST API

### Common Development Settings
- **Memory**: Adjust JVM heap size based on map size (use `-Xmx` flag)
- **Data Access**: Choose between RAM_STORE (fast) or MMAP (memory-efficient)
- **Profiles**: Enable/disable vehicle types and customization options

## Important Development Notes

### Code Style
- Java 8+ required
- 4-space indentation, 100-character line limit
- IntelliJ IDEA defaults, EditorConfig support
- Unix line endings (LF)
- Extensive test coverage expected

### Performance Considerations
- Graph preprocessing (CH/LM) trades memory/startup time for query speed
- Memory-mapped storage enables handling large maps with limited RAM
- Encoded values system allows efficient storage of routing properties
- Contraction and landmarks dramatically improve routing performance

### Common Customization Points
- **Custom Models**: Modify routing behavior via JSON without code changes
- **Tag Parsers**: Extract custom properties from OSM data
- **Weighting**: Implement custom cost functions
- **Encoded Values**: Add new edge properties for routing decisions