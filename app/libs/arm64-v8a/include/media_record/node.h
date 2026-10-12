#ifndef MEDIA_RECORD_NODE_H_
#define MEDIA_RECORD_NODE_H_

#include <functional>
#include <map>
#include <memory>
#include <string>

#include "media_record/media_record_export.h"

// Node contract skeleton for media_record.
//
// Skeleton only (spec 001): the lifecycle mirrors graph_runtime's
// Open/Process/Close NodeContract so business nodes can be slotted in later
// without changing the runtime. Real stream/packet types are wired by follow-up
// features; this header stays dependency-free so the public target compiles
// standalone (header-only).

namespace media::record {

// Lightweight status for the skeleton lifecycle. Replaced by the shared
// error model in a later feature.
struct MEDIA_RECORD_API NodeStatus {
  bool ok = true;
  std::string message;
};

class MEDIA_RECORD_API Node {
 public:
  virtual ~Node() = default;

  // One-time setup, called when the graph opens the node.
  virtual NodeStatus Open() { return {}; }
  // Per-data/invocation processing, called by the scheduler.
  virtual NodeStatus Process() { return {}; }
  // One-time teardown, called when the graph closes the node.
  virtual NodeStatus Close() { return {}; }
};

// --- Registration skeleton ------------------------------------------------
// A string-keyed factory registry aligned with graph_runtime NodeFactoryRegistry
// semantics: configs reference nodes by name only. Header-only implementation.

using NodeFactory = std::function<std::unique_ptr<Node>()>;

class MEDIA_RECORD_API NodeRegistry {
 public:
  static NodeRegistry& Instance() {
    static NodeRegistry registry;
    return registry;
  }

  bool Register(const std::string& name, NodeFactory factory) {
    return factories_.emplace(name, std::move(factory)).second;
  }

  std::unique_ptr<Node> Create(const std::string& name) const {
    auto it = factories_.find(name);
    return it != factories_.end() ? it->second() : nullptr;
  }

  bool Contains(const std::string& name) const {
    return factories_.count(name) > 0;
  }

 private:
  NodeRegistry() = default;
  std::map<std::string, NodeFactory> factories_;
};

// Registers |Type| under |name| (a string literal) at static-init time.
// Usage: REGISTER_NODE("MyNode", MyNode)
// The backing identifier uses the |Type| token (like graph_runtime's
// GRAPH_RUNTIME_REGISTER_NODE) so |name| may be an arbitrary string literal.
#define MEDIA_RECORD_REGISTER_NODE(name, Type)                        \
  [[maybe_unused]] static const bool media_record_register_##Type##_ = \
      ::media::record::NodeRegistry::Instance().Register(              \
          name, [] { return std::make_unique<Type>(); })

// Alias kept short for node headers (macro registry style of graph_runtime).
#define REGISTER_NODE(name, Type) MEDIA_RECORD_REGISTER_NODE(name, Type)

}  // namespace media::record

#endif  // MEDIA_RECORD_NODE_H_
