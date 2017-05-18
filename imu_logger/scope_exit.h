#ifndef _SCOPE_EXIT_H_
#define _SCOPE_EXIT_H_

// http://the-witness.net/news/2012/11/scopeexit-in-c11/

template <typename F>
struct ScopeExit {
    ScopeExit(F f) : f(f) {}
    ~ScopeExit() { f(); }
    F f;
};

template <typename F>
ScopeExit<F> MakeScopeExit(F f) {
    return ScopeExit<F>(f);
};

#define _SCOPE_EXIT_STRING_JOIN2(arg1, arg2) \
  _SCOPE_EXIT_DO_STRING_JOIN2(arg1, arg2)
#define _SCOPE_EXIT_DO_STRING_JOIN2(arg1, arg2) arg1 ## arg2
#define SCOPE_EXIT(code) \
    auto _SCOPE_EXIT_STRING_JOIN2(scope_exit_, __LINE__) = \
        MakeScopeExit([=](){code;})

#endif
