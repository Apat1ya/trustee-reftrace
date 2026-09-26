package dev.reftrace.run;

record RunId(String value) {

    RunId {
        if (value.isBlank()) {
            throw new IllegalArgumentException("run id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
